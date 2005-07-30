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
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.Peer;
import freenet.io.comm.PeerContext;
import freenet.io.comm.UdpSocketManager;
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
		_sentPackets = new BitArray(_prb.getNumPackets());
	}

	public boolean send() {
		final PacketThrottle throttle = PacketThrottle.getThrottle(_destination.getPeer(), _prb._packetSize);
		_receiverThread = Thread.currentThread();
		_senderThread = new Thread() {
		    
			public void run() {
				int sentSinceLastPing = 0;
				while (!_sendComplete) {
						long waitUntil = System.currentTimeMillis() + throttle.getDelay();
						try {
							while (waitUntil > System.currentTimeMillis()) {
								synchronized (_senderThread) {
								_senderThread.wait(waitUntil - System.currentTimeMillis());
								}
							}
							while (_unsent.size() == 0) {
								synchronized (_senderThread) {
									_senderThread.wait();
								}
							}
						} catch (InterruptedException e) {  }
						int packetNo = ((Integer) _unsent.removeFirst()).intValue();
						_sentPackets.setBit(packetNo, true);
						_usm.send(BlockTransmitter.this._destination, DMT.createPacketTransmit(_uid, packetNo, _sentPackets, _prb.getPacket(packetNo)));
						
						// We accelerate the ping rate during the transfer to keep a closer eye on round-trip-time
						sentSinceLastPing++;
						if (sentSinceLastPing >= PING_EVERY) {
							sentSinceLastPing = 0;
							_usm.send(BlockTransmitter.this._destination, DMT.createPing());
						}
						throttle.notifyOfPacketSent();
				}
			}
		};
		
		_unsent = _prb.addListener(new PartiallyReceivedBlock.PacketReceivedListener() {;

			public void packetReceived(int packetNo) {
				_unsent.addLast(new Integer(packetNo));
				_sentPackets.setBit(packetNo, false);
				synchronized(_senderThread) {
					_senderThread.notify();
				}
			}

			public void receiveAborted(int reason, String description) {
				_usm.send(_destination, DMT.createSendAborted(reason, description));
			} });

		_senderThread.start();
		
		while (true) {
			if (_prb.isAborted()) {
				_sendComplete = true;
				return false;
			}
			Message msg = _usm.waitFor(MessageFilter.create().setTimeout(SEND_TIMEOUT).setType(DMT.missingPacketNotification).setField(DMT.UID, _uid).or(MessageFilter.create().setType(DMT.allReceived).setField(DMT.UID, _uid)));
			if (msg == null) {
				if (getNumSent() == _prb.getNumPackets()) {
					_sendComplete = true;
					Logger.normal(this, "Terminating send as we haven't heard from receiver in "+SEND_TIMEOUT+"ms.");
					return false;
				}
			} else if (msg.getSpec().equals(DMT.missingPacketNotification)) {
				LinkedList missing = (LinkedList) msg.getObject(DMT.MISSING);
				throttle.notifyOfPacketLoss(missing.size());
				for (Iterator i = missing.iterator(); i.hasNext();) {
					Integer packetNo = (Integer) i.next();
					if (_prb.isReceived(packetNo.intValue())) {
					    _unsent.addFirst(packetNo);
					    _sentPackets.setBit(packetNo.intValue(), false);
					    synchronized(_senderThread) {
					        _senderThread.notify();
					    }
					}
				}
			} else if (msg.getSpec().equals(DMT.allReceived)) {
				_sendComplete = true;
				return true;
			}
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
