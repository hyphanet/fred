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
 * 
 * To change the template for this generated type comment go to Window - Preferences - Java - Code Generation - Code and
 * Comments
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
	}

	public boolean send() {
		final PacketThrottle throttle = PacketThrottle.getThrottle(_destination.getPeer(), _prb._packetSize);
		_receiverThread = Thread.currentThread();
		_senderThread = new Thread("_senderThread for "+_uid) {
		    
			public void run() {
				int sentSinceLastPing = 0;
				while (!_sendComplete) {
						long delay = throttle.getDelay();
						long waitUntil = System.currentTimeMillis() + delay;
						Logger.minor(this, "Waiting for "+delay+" ms for "+_uid+" : "+throttle);
						try {
							while (waitUntil > System.currentTimeMillis()) {
								if(_sendComplete) return;
								synchronized (_senderThread) {
									long x = waitUntil - System.currentTimeMillis();
									if(x > 0)
										_senderThread.wait(x);
								}
							}
							while (true) {
								synchronized(_unsent) {
									if(_unsent.size() != 0) break;
								}
								if(_sendComplete) return;
								synchronized (_senderThread) {
									_senderThread.wait();
								}
							}
						} catch (InterruptedException e) {  }
						int packetNo;
						synchronized(_unsent) {
							packetNo = ((Integer) _unsent.removeFirst()).intValue();
						}
						_sentPackets.setBit(packetNo, true);
						try {
							((PeerNode)_destination).sendAsync(DMT.createPacketTransmit(_uid, packetNo, _sentPackets, _prb.getPacket(packetNo)), null);
						// We accelerate the ping rate during the transfer to keep a closer eye on round-trip-time
						sentSinceLastPing++;
						if (sentSinceLastPing >= PING_EVERY) {
							sentSinceLastPing = 0;
							//_usm.send(BlockTransmitter.this._destination, DMT.createPing());
							((PeerNode)_destination).sendAsync(DMT.createPing(), null);
						}
						} catch (NotConnectedException e) {
						    Logger.normal(this, "Terminating send: "+e);
						    _sendComplete = true;
						} catch (AbortedException e) {
							Logger.normal(this, "Terminating send due to abort: "+e);
							_sendComplete = true;
						}
				}
			}
		};
		
		try {
		_unsent = _prb.addListener(new PartiallyReceivedBlock.PacketReceivedListener() {;

			public void packetReceived(int packetNo) {
				_unsent.addLast(new Integer(packetNo));
				_sentPackets.setBit(packetNo, false);
				synchronized(_senderThread) {
					_senderThread.notify();
				}
			}

			public void receiveAborted(int reason, String description) {
				try {
					((PeerNode)_destination).sendAsync(DMT.createSendAborted(reason, description), null);
                } catch (NotConnectedException e) {
                    Logger.minor(this, "Receive aborted and receiver is not connected");
                }
			} });

		_senderThread.start();
		
		while (true) {
			if (_prb.isAborted()) {
				_sendComplete = true;
				return false;
			}
			Message msg;
			try {
                msg = _usm.waitFor(MessageFilter.create().setTimeout(SEND_TIMEOUT).setType(DMT.missingPacketNotification).setField(DMT.UID, _uid).or(MessageFilter.create().setType(DMT.allReceived).setField(DMT.UID, _uid)));
            } catch (DisconnectedException e) {
                Logger.normal(this, "Terminating send "+_uid+" to "+_destination+" from "+_usm.getPortNumber()+" because node disconnected while waiting");
                _sendComplete = true;
                return false;
            }
			if(_sendComplete || !_destination.isConnected()) return false;
			if (msg == null) {
				if (getNumSent() == _prb.getNumPackets()) {
					_sendComplete = true;
					Logger.error(this, "Terminating send "+_uid+" to "+_destination+" from "+_usm.getPortNumber()+" as we haven't heard from receiver in "+SEND_TIMEOUT+"ms.");
					return false;
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
				_sendComplete = true;
				return true;
			} else if(_sendComplete) {
			    // Terminated abnormally
			    return false;
			}
		}
		} catch (AbortedException e) {
			// Terminate
			_sendComplete = true;
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
}
