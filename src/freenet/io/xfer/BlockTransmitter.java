/*
 * Dijjer - A Peer to Peer HTTP Cache
 * Copyright (C) 2004,2005 Change.Tv, Inc
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package freenet.io.xfer;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.NoSuchElementException;

import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.PeerContext;
import freenet.io.comm.UdpSocketManager;
import freenet.node.ByteCounter;
import freenet.node.FNPPacketMangler;
import freenet.node.PeerNode;
import freenet.support.BitArray;
import freenet.support.DoubleTokenBucket;
import freenet.support.Logger;
import freenet.support.TimeUtil;

/**
 * @author ian
 */
public class BlockTransmitter {

	public static final int SEND_TIMEOUT = 60000;
	public static final int PING_EVERY = 8;
	
	UdpSocketManager _usm;
	PeerContext _destination;
	boolean _sendComplete;
	long _uid;
	PartiallyReceivedBlock _prb;
	LinkedList _unsent;
	Thread _receiverThread, _senderThread;
	BitArray _sentPackets;
	boolean failedByOverload;
	final PacketThrottle throttle;
	long timeAllSent = -1;
	final DoubleTokenBucket _masterThrottle;
	final ByteCounter _ctr;
	final int PACKET_SIZE;
	
	public BlockTransmitter(UdpSocketManager usm, PeerContext destination, long uid, PartiallyReceivedBlock source, DoubleTokenBucket masterThrottle, ByteCounter ctr) {
		_usm = usm;
		_destination = destination;
		_uid = uid;
		_prb = source;
		_ctr = ctr;
		_masterThrottle = masterThrottle;
		PACKET_SIZE = DMT.packetTransmitSize(_prb._packetSize, _prb._packets)
			+ FNPPacketMangler.HEADERS_LENGTH_ONE_MESSAGE;
		try {
			_sentPackets = new BitArray(_prb.getNumPackets());
		} catch (AbortedException e) {
			Logger.error(this, "Aborted during setup");
			// Will throw on running
		}
		throttle = PacketThrottle.getThrottle(_destination.getPeer(), _prb._packetSize);
		_senderThread = new Thread("_senderThread for "+_uid+ " to "+_destination.getPeer()) {
		    
			public void run() {
				int sentSinceLastPing = 0;
				while (!_sendComplete) {
						long startCycleTime = System.currentTimeMillis();
						try {
							while (true) {
								synchronized(_senderThread) {
									if(_unsent.size() != 0) break;
									// No unsent packets
									if(getNumSent() == _prb.getNumPackets()) {
										if(Logger.shouldLog(Logger.MINOR, this))
											Logger.minor(this, "Sent all blocks, none unsent");
										if(timeAllSent <= 0)
											timeAllSent = System.currentTimeMillis();
									}
									if(_sendComplete) return;
									_senderThread.wait(10*1000);
								}
							}
							timeAllSent = -1;
						} catch (InterruptedException e) {
						} catch (AbortedException e) {
							synchronized(_senderThread) {
								_sendComplete = true;
								_senderThread.notifyAll();
							}
							return;
						}
						int packetNo;
						try {
							synchronized(_senderThread) {
								packetNo = ((Integer) _unsent.removeFirst()).intValue();
							}
						} catch (NoSuchElementException nsee) {
							// back up to the top to check for completion
							continue;
						}
						delay(startCycleTime);
						if(_sendComplete) break;
						_sentPackets.setBit(packetNo, true);
						try {
							((PeerNode)_destination).sendAsync(DMT.createPacketTransmit(_uid, packetNo, _sentPackets, _prb.getPacket(packetNo)), null, PACKET_SIZE, _ctr);
							_ctr.sentPayload(PACKET_SIZE);
						// We accelerate the ping rate during the transfer to keep a closer eye on round-trip-time
						sentSinceLastPing++;
						if (sentSinceLastPing >= PING_EVERY) {
							sentSinceLastPing = 0;
							//_usm.send(BlockTransmitter.this._destination, DMT.createPing());
							((PeerNode)_destination).sendAsync(DMT.createPing(), null, 0, _ctr);
						}
						} catch (NotConnectedException e) {
						    Logger.normal(this, "Terminating send: "+e);
						    synchronized(_senderThread) {
						    	_sendComplete = true;
						    	_senderThread.notifyAll();
						    }
						} catch (AbortedException e) {
							Logger.normal(this, "Terminating send due to abort: "+e);
							synchronized(_senderThread) {
								_sendComplete = true;
								_senderThread.notifyAll();
							}
						}
				}
			}

			/** @return True if _sendComplete */
			private void delay(long startCycleTime) {
				
				long startThrottle = System.currentTimeMillis();

				// Get the current inter-packet delay
				long end = throttle.scheduleDelay(startThrottle);

				_masterThrottle.blockingGrab(PACKET_SIZE);
				
				long now = System.currentTimeMillis();
				
				long delayTime = now - startThrottle;
				
				// Report the delay caused by bandwidth limiting, NOT the delay caused by congestion control.
				((PeerNode)_destination).reportThrottledPacketSendTime(delayTime);
				
				if(now > end) return;
				while(now < end) {
					long l = end - now;
					synchronized(_senderThread) {
						if(_sendComplete) return;
					}
					// Check for completion every 2 minutes
					int x = (int) (Math.min(l, 120*1000));
					if(x > 0) {
						try {
							Thread.sleep(x);
						} catch (InterruptedException e) {
							// Ignore
						}
						now = System.currentTimeMillis();
					}
				}
			}
		};
		_senderThread.setDaemon(true);
	}

	public void sendAborted(int reason, String desc) throws NotConnectedException {
		_usm.send(_destination, DMT.createSendAborted(_uid, reason, desc), _ctr);
	}
	
	public boolean send() {
		_receiverThread = Thread.currentThread();
		
		PartiallyReceivedBlock.PacketReceivedListener myListener;
		
		try {
			synchronized(_prb) {
		_unsent = _prb.addListener(myListener = new PartiallyReceivedBlock.PacketReceivedListener() {;

			public void packetReceived(int packetNo) {
				synchronized(_senderThread) {
					_unsent.addLast(new Integer(packetNo));
					_sentPackets.setBit(packetNo, false);
					_senderThread.notify();
				}
			}

			public void receiveAborted(int reason, String description) {
				try {
					((PeerNode)_destination).sendAsync(DMT.createSendAborted(_uid, reason, description), null, 0, _ctr);
                } catch (NotConnectedException e) {
                    if(Logger.shouldLog(Logger.MINOR, this))
                    	Logger.minor(this, "Receive aborted and receiver is not connected");
                }
			} });
			}
		_senderThread.start();
		
		while (true) {
			if (_prb.isAborted()) {
				synchronized(_senderThread) {
					_sendComplete = true;
					_senderThread.notifyAll();
				}
				return false;
			}
			Message msg;
			boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
			try {
				MessageFilter mfMissingPacketNotification = MessageFilter.create().setType(DMT.missingPacketNotification).setField(DMT.UID, _uid).setTimeout(SEND_TIMEOUT).setSource(_destination);
				MessageFilter mfAllReceived = MessageFilter.create().setType(DMT.allReceived).setField(DMT.UID, _uid).setTimeout(SEND_TIMEOUT).setSource(_destination);
				MessageFilter mfSendAborted = MessageFilter.create().setType(DMT.sendAborted).setField(DMT.UID, _uid).setTimeout(SEND_TIMEOUT).setSource(_destination);
                msg = _usm.waitFor(mfMissingPacketNotification.or(mfAllReceived.or(mfSendAborted)), _ctr);
                if(logMINOR) Logger.minor(this, "Got "+msg);
            } catch (DisconnectedException e) {
            	// Ignore, see below
            	msg = null;
            }
            if(logMINOR) Logger.minor(this, "Got "+msg);
            if(!_destination.isConnected()) {
                Logger.normal(this, "Terminating send "+_uid+" to "+_destination+" from "+_usm.getPortNumber()+" because node disconnected while waiting");
                synchronized(_senderThread) {
                	_sendComplete = true;
                	_senderThread.notifyAll();
                }
                return false;
            }
            if(_sendComplete)
            	return false;
			if (msg == null) {
				long now = System.currentTimeMillis();
				if((timeAllSent > 0) && ((now - timeAllSent) > SEND_TIMEOUT) &&
						(getNumSent() == _prb.getNumPackets())) {
					synchronized(_senderThread) {
						_sendComplete = true;
						_senderThread.notifyAll();
					}
					Logger.error(this, "Terminating send "+_uid+" to "+_destination+" from "+_usm.getPortNumber()+" as we haven't heard from receiver in "+TimeUtil.formatTime((now - timeAllSent), 2, true)+".");
					return false;
				} else {
					if(logMINOR) Logger.minor(this, "Ignoring timeout: timeAllSent="+timeAllSent+" ("+(System.currentTimeMillis() - timeAllSent)+"), getNumSent="+getNumSent()+"/"+_prb.getNumPackets());
					continue;
				}
			} else if (msg.getSpec().equals(DMT.missingPacketNotification)) {
				LinkedList missing = (LinkedList) msg.getObject(DMT.MISSING);
				for (Iterator i = missing.iterator(); i.hasNext();) {
					Integer packetNo = (Integer) i.next();
					if (_prb.isReceived(packetNo.intValue())) {
						synchronized(_senderThread) {
							_unsent.addFirst(packetNo);
						    _sentPackets.setBit(packetNo.intValue(), false);
					        _senderThread.notify();
						}
					}
				}
			} else if (msg.getSpec().equals(DMT.allReceived)) {
				synchronized(_senderThread) {
					_sendComplete = true;
					_senderThread.notifyAll();
				}
				return true;
			} else if (msg.getSpec().equals(DMT.sendAborted)) {
				// Overloaded: receiver no longer wants the data
				// Do NOT abort PRB, it's none of its business.
				// And especially, we don't want a downstream node to 
				// be able to abort our sends to all the others!
				_prb.removeListener(myListener);
				synchronized(_senderThread) {
					_sendComplete = true;
					_senderThread.notifyAll();
				}
				return false;
			} else if(_sendComplete) {
			    // Terminated abnormally
			    return false;
			}
		}
		} catch (AbortedException e) {
			// Terminate
			synchronized(_senderThread) {
				_sendComplete = true;
				_senderThread.notifyAll();
			}
			return false;
		}
	}

	public int getNumSent() {
		int ret = 0;
		for (int x=0; x<_sentPackets.getSize(); x++) {
			if (_sentPackets.bitAt(x)) {
				ret++;
			}
		}
		return ret;
	}

    /**
     * Send the data, off-thread.
     */
    public void sendAsync() {
        Runnable r = new Runnable() {
            public void run() { send(); } };
        Thread t = new Thread(r, "BlockTransmitter:sendAsync() for "+this);
        t.setDaemon(true);
        t.start();
    }

	public void waitForComplete() {
		synchronized(_senderThread) {
			while(!_sendComplete) {
				try {
					_senderThread.wait(10*1000);
				} catch (InterruptedException e) {
					// Ignore
				}
			}
		}
	}

	public boolean failedDueToOverload() {
		return failedByOverload;
	}

	public PeerContext getDestination() {
		return _destination;
	}
	
}
