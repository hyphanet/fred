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

import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.PeerContext;
import freenet.io.comm.UdpSocketManager;
import freenet.node.PeerNode;
import freenet.support.BitArray;
import freenet.support.Logger;

/**
 * @author ian
 */
public class BlockTransmitter {

	public static final int SEND_TIMEOUT = 30000;
	public static final int PING_EVERY = 8;
	
	UdpSocketManager _usm;
	PeerContext _destination;
	boolean _sendComplete = false;
	long _uid;
	PartiallyReceivedBlock _prb;
	LinkedList _unsent;
	Thread _receiverThread, _senderThread;
	BitArray _sentPackets;
	boolean failedByOverload = false;
	final PacketThrottle throttle;
	long timeAllSent = -1;
	
	// FIXME make this stuff non-static. Have a context object for limiting.
	
	// Static stuff for global bandwidth limiter
	/** Synchronization object for bandwidth limiting */
	static final Object lastPacketSendTimeSync = new Object();
	// Use nanosecond long values for accuracy reasons
	/** Time at which the last known packet is scheduled to be sent.
	 * We will not send another packet until at least minPacketDelay ms after this time. */
	static long hardLastPacketSendTimeNSec = System.currentTimeMillis() * 1000*1000;
	/** Minimum interval between packet sends, for overall hard bandwidth limiter */
	static int minPacketDelayNSec = 0;
	/** Minimum average interval between packet sends, for averaged (soft) overall
	 * bandwidth usage limiter. */
	static int minSoftDelayNSec = 0;
	/** "Soft" equivalent to hardLastPacketSendTime. Can lag up to half the softLimitPeriod
	 * behind the current time. Otherwise is similar. This gives it flexibility; we can have
	 * spurts above the average limit, but over the softLimitPeriod, it will average out to 
	 * the target minSoftDelay. */
	static long softLastPacketSendTimeNSec = System.currentTimeMillis() * 1000*1000;
	/** Period over which the soft limiter should work */
	static long softLimitPeriodNSec;

	/**
	 * Set the hard bandwidth limiter.
	 * @param bytesPerSecond The maximum number of bytes (of data blocks) to be sent in any
	 * one second.
	 */
	public static void setHardBandwidthLimit(int bytesPerSecond) {
		int newMinPacketDelayNS = convertBytesPerSecondToNanosPerPacket(bytesPerSecond);
		synchronized(lastPacketSendTimeSync) {
			if(minPacketDelayNSec != newMinPacketDelayNS) {
				minPacketDelayNSec = newMinPacketDelayNS;
				hardLastPacketSendTimeNSec = System.currentTimeMillis() * 1000*1000;
			}
		}
	}

	public static int convertBytesPerSecondToNanosPerPacket(int bytesPerSecond) {
		if(bytesPerSecond <= 0)
			return 0; // no limits
		
		int packetSize = getPacketSize();
		double minNanoSecondsBetweenPackets =
			((1000.0*1000.0*1000.0) * packetSize) / ((double) bytesPerSecond);
		int newMinPacketDelayNS = (int) minNanoSecondsBetweenPackets;
		double inaccuracy = minNanoSecondsBetweenPackets - newMinPacketDelayNS;
		double inaccuracyPercent = (inaccuracy / minNanoSecondsBetweenPackets) * 100.0;
		Logger.minor(BlockTransmitter.class, "Quantization inaccuracy: "+inaccuracyPercent+"%");
		return newMinPacketDelayNS;
	}
	
	public static int convertBytesPerPeriodToNanosPerPacket(int bytesPerSecond, long periodLengthNanos) {
		if(bytesPerSecond <= 0)
			return 0; // no limits
		
		int packetSize = getPacketSize();
		double minNanoSecondsBetweenPackets =
			(periodLengthNanos * packetSize) / ((double) bytesPerSecond);
		int newMinPacketDelayNS = (int) minNanoSecondsBetweenPackets;
		double inaccuracy = minNanoSecondsBetweenPackets - newMinPacketDelayNS;
		double inaccuracyPercent = (inaccuracy / minNanoSecondsBetweenPackets) * 100.0;
		Logger.minor(BlockTransmitter.class, "Quantization inaccuracy: "+inaccuracyPercent+"%");
		return newMinPacketDelayNS;
	}
	
	public static int convertNanosPerPacketToBytesPerSecond(int delay) {
		if(delay == 0) return 0;
		return (int) (((1000.0*1000.0*1000.0) * getPacketSize()) / ((double)delay));
	}

	/** @return The average packet size for a block sent by a BlockTransmitter, including all 
	 * headers and protocol overhead */
	private static int getPacketSize() {
		// FIXME make this more accurate!
		return 1024 + 200;
	}

	public static int getHardBandwidthLimit() {
		int delay;
		synchronized(lastPacketSendTimeSync) {
			delay = minPacketDelayNSec;
		}
		return convertNanosPerPacketToBytesPerSecond(delay);
	}

	/**
	 * Set the long-term bandwidth limiter.
	 * @param bytes The number of bytes to allow at most over the period. (in data packets)
	 * @param period The length of time over which the limit should apply. (ms)
	 */
	public static void setSoftBandwidthLimit(int bytes, long period) {
		if(period > Long.MAX_VALUE / (1000*1000)) throw new IllegalArgumentException("Period too long");
		int newSoftLimit = convertBytesPerPeriodToNanosPerPacket(bytes, period);
		period = period * 1000 * 1000;
		synchronized(lastPacketSendTimeSync) {
			minSoftDelayNSec = newSoftLimit;
			softLimitPeriodNSec = period;
			long nowNS = System.currentTimeMillis() * 1000 * 1000;
			if(nowNS - softLastPacketSendTimeNSec > period / 2) {
				softLastPacketSendTimeNSec = nowNS - (period / 2);
			}
		}
	}
	
	public BlockTransmitter(UdpSocketManager usm, PeerContext destination, long uid, PartiallyReceivedBlock source) {
		_usm = usm;
		_destination = destination;
		_uid = uid;
		_prb = source;
		try {
			_sentPackets = new BitArray(_prb.getNumPackets());
		} catch (AbortedException e) {
			Logger.error(this, "Aborted during setup");
			// Will throw on running
		}
		throttle = PacketThrottle.getThrottle(_destination.getPeer(), _prb._packetSize);
		_senderThread = new Thread("_senderThread for "+_uid) {
		    
			public void run() {
				int sentSinceLastPing = 0;
				while (!_sendComplete) {
						long startCycleTime = System.currentTimeMillis();
						try {
							while (true) {
								synchronized(_unsent) {
									if(_unsent.size() != 0) break;
									// No unsent packets
									if(getNumSent() == _prb.getNumPackets()) {
										Logger.minor(this, "Sent all blocks, none unsent");
										if(timeAllSent <= 0)
											timeAllSent = System.currentTimeMillis();
									}
								}
								if(_sendComplete) return;
								synchronized (_senderThread) {
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
						long startDelayTime = System.currentTimeMillis();
						delay(startCycleTime);
						int packetNo;
						synchronized(_unsent) {
							packetNo = ((Integer) _unsent.removeFirst()).intValue();
						}
						_sentPackets.setBit(packetNo, true);
						try {
							long endDelayTime = System.currentTimeMillis();
							long delayTime = endDelayTime - startDelayTime;
							((PeerNode)_destination).reportThrottledPacketSendTime(delayTime);
							((PeerNode)_destination).sendAsync(DMT.createPacketTransmit(_uid, packetNo, _sentPackets, _prb.getPacket(packetNo)), null);
							// May have been delays in sending, so update to avoid sending more frequently than allowed
							long nowNS = System.currentTimeMillis() * 1000 * 1000;
							synchronized(lastPacketSendTimeSync) {
								if(hardLastPacketSendTimeNSec < nowNS)
									hardLastPacketSendTimeNSec = nowNS;
							}
						// We accelerate the ping rate during the transfer to keep a closer eye on round-trip-time
						sentSinceLastPing++;
						if (sentSinceLastPing >= PING_EVERY) {
							sentSinceLastPing = 0;
							//_usm.send(BlockTransmitter.this._destination, DMT.createPing());
							((PeerNode)_destination).sendAsync(DMT.createPing(), null);
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
			private boolean delay(long startCycleTime) {
				
				// Get the current inter-packet delay
				long delay = throttle.getDelay();
				
				while(true) {
					
					long nowNS = System.currentTimeMillis() * 1000 * 1000;
					
					long endTime = -1;
					
					boolean thenSend = true;
					
					// Synchronize on the static lock, and update
					synchronized(lastPacketSendTimeSync) {
						
						// Get the current time
						nowNS = System.currentTimeMillis() * 1000 * 1000;
						
						// Update time if necessary to avoid spurts
						if(hardLastPacketSendTimeNSec < (nowNS - minPacketDelayNSec))
							hardLastPacketSendTimeNSec = nowNS - minPacketDelayNSec;
						
						// Wait until the next send window
						long newHardLastPacketSendTimeNS =
							hardLastPacketSendTimeNSec + minPacketDelayNSec;
						
						long newHardLastPacketSendTime =
							newHardLastPacketSendTimeNS / (1000 * 1000);
						
						long earliestSendTime = startCycleTime + delay;
						
						if(earliestSendTime > newHardLastPacketSendTime) {
							// Don't clog up other senders!
							thenSend = false;
							endTime = earliestSendTime;
						} else {
							hardLastPacketSendTimeNSec = newHardLastPacketSendTimeNS;
							endTime = hardLastPacketSendTimeNSec / (1000 * 1000);
							
							// What about the soft limit?
							
							// We can only accumulate burst traffic rights for a full period at most.
							// If we have a period of 1 hour, and we send no traffic in the first 30 minutes,
							// then we can use up our whole hour's quota in the next 30 minutes if we need to.
							// We could even use our entire quota in the last 5 minutes. After that, we can
							// only send at the limit (which may be very low), since we have no quota left.
							// However, after 1 hour we forget our burst rights.
							if(nowNS - softLastPacketSendTimeNSec > softLimitPeriodNSec) {
								softLastPacketSendTimeNSec = nowNS - (softLimitPeriodNSec);
							}
							
							softLastPacketSendTimeNSec += minSoftDelayNSec;
							
							if(softLastPacketSendTimeNSec > hardLastPacketSendTimeNSec) {
								endTime = ((hardLastPacketSendTimeNSec = softLastPacketSendTimeNSec) / (1000 * 1000));
							}
						}
					}
					
					long now = nowNS / (1000 * 1000);
					
					while(nowNS < endTime) {
						synchronized(_senderThread) {
							if(_sendComplete)
								return true;
							try {
								_senderThread.wait(endTime - now);
							} catch (InterruptedException e) {
								// Ignore
							}
						}
						if(_sendComplete)
							return true;
						now = System.currentTimeMillis();
					}
					
					nowNS = now * 1000 * 1000;
					
					if(thenSend) return false;
				}
			}
		};
	}

	public void sendAborted(int reason, String desc) throws NotConnectedException {
		_usm.send(_destination, DMT.createSendAborted(_uid, reason, desc));
	}
	
	public boolean send() {
		_receiverThread = Thread.currentThread();
		
		PartiallyReceivedBlock.PacketReceivedListener myListener;
		
		try {
		_unsent = _prb.addListener(myListener = new PartiallyReceivedBlock.PacketReceivedListener() {;

			public void packetReceived(int packetNo) {
				_unsent.addLast(new Integer(packetNo));
				_sentPackets.setBit(packetNo, false);
				synchronized(_senderThread) {
					_senderThread.notify();
				}
			}

			public void receiveAborted(int reason, String description) {
				try {
					((PeerNode)_destination).sendAsync(DMT.createSendAborted(_uid, reason, description), null);
                } catch (NotConnectedException e) {
                    Logger.minor(this, "Receive aborted and receiver is not connected");
                }
			} });

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
			try {
				MessageFilter mfMissingPacketNotification = MessageFilter.create().setType(DMT.missingPacketNotification).setField(DMT.UID, _uid).setTimeout(SEND_TIMEOUT).setSource(_destination);
				MessageFilter mfAllReceived = MessageFilter.create().setType(DMT.allReceived).setField(DMT.UID, _uid).setTimeout(SEND_TIMEOUT).setSource(_destination);
				MessageFilter mfSendAborted = MessageFilter.create().setType(DMT.sendAborted).setField(DMT.UID, _uid).setTimeout(SEND_TIMEOUT).setSource(_destination);
                msg = _usm.waitFor(mfMissingPacketNotification.or(mfAllReceived.or(mfSendAborted)));
                Logger.minor(this, "Got "+msg);
            } catch (DisconnectedException e) {
            	// Ignore, see below
            	msg = null;
            }
            Logger.minor(this, "Got "+msg);
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
				if(timeAllSent > 0 && (System.currentTimeMillis() - timeAllSent) > SEND_TIMEOUT &&
						getNumSent() == _prb.getNumPackets()) {
					synchronized(_senderThread) {
						_sendComplete = true;
						_senderThread.notifyAll();
					}
					Logger.error(this, "Terminating send "+_uid+" to "+_destination+" from "+_usm.getPortNumber()+" as we haven't heard from receiver in "+SEND_TIMEOUT+"ms.");
					return false;
				} else {
					Logger.minor(this, "Ignoring timeout: timeAllSent="+timeAllSent+" ("+(System.currentTimeMillis() - timeAllSent)+"), getNumSent="+getNumSent()+"/"+_prb.getNumPackets());
					continue;
				}
			} else if (msg.getSpec().equals(DMT.missingPacketNotification)) {
				LinkedList missing = (LinkedList) msg.getObject(DMT.MISSING);
				for (Iterator i = missing.iterator(); i.hasNext();) {
					Integer packetNo = (Integer) i.next();
					if (_prb.isReceived(packetNo.intValue())) {
						synchronized(_unsent) {
							_unsent.addFirst(packetNo);
						}
					    _sentPackets.setBit(packetNo.intValue(), false);
					    synchronized(_senderThread) {
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
        Thread t = new Thread(r);
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
