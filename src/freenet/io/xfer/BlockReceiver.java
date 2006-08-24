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

import java.util.HashMap;
import java.util.LinkedList;

import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.PeerContext;
import freenet.io.comm.RetrievalException;
import freenet.io.comm.UdpSocketManager;
import freenet.node.ByteCounter;
import freenet.support.BitArray;
import freenet.support.Buffer;
import freenet.support.Logger;

/**
 * @author ian
 */
public class BlockReceiver {

	public static final int RECEIPT_TIMEOUT = 15000;
	// TODO: This should be proportional to the calculated round-trip-time, not a constant
	public static final int MAX_ROUND_TRIP_TIME = RECEIPT_TIMEOUT;
	public static final int MAX_CONSECUTIVE_MISSING_PACKET_REPORTS = 4;
	public static final int MAX_SEND_INTERVAL = 500;
	PartiallyReceivedBlock _prb;
	PeerContext _sender;
	long _uid;
	UdpSocketManager _usm;
	/** packet : Integer -> reportTime : Long * */
	HashMap _recentlyReportedMissingPackets = new HashMap();
	ByteCounter _ctr;

	public BlockReceiver(UdpSocketManager usm, PeerContext sender, long uid, PartiallyReceivedBlock prb, ByteCounter ctr) {
		_sender = sender;
		_prb = prb;
		_uid = uid;
		_usm = usm;
		_ctr = ctr;
	}

	public void sendAborted(int reason, String desc) throws NotConnectedException {
		_usm.send(_sender, DMT.createSendAborted(_uid, reason, desc), _ctr);
	}
	
	public byte[] receive() throws RetrievalException {
		int consecutiveMissingPacketReports = 0;
		try {
		while (!_prb.allReceived()) {
			Message m1;
            try {
            	MessageFilter mfPacketTransmit = MessageFilter.create().setTimeout(RECEIPT_TIMEOUT).setType(DMT.packetTransmit).setField(DMT.UID, _uid).setSource(_sender);
            	MessageFilter mfAllSent = MessageFilter.create().setType(DMT.allSent).setField(DMT.UID, _uid).setSource(_sender);
            	MessageFilter mfSendAborted = MessageFilter.create().setType(DMT.sendAborted).setField(DMT.UID, _uid).setSource(_sender);
                m1 = _usm.waitFor(mfPacketTransmit.or(mfAllSent.or(mfSendAborted)), _ctr);
                if(!_sender.isConnected()) throw new DisconnectedException();
            } catch (DisconnectedException e1) {
                Logger.normal(this, "Disconnected during receive: "+_uid+" from "+_sender);
                throw new RetrievalException(RetrievalException.SENDER_DISCONNECTED);
            }
            if(Logger.shouldLog(Logger.MINOR, this))
            	Logger.minor(this, "Received "+m1);
            if ((m1 != null) && m1.getSpec().equals(DMT.sendAborted)) {
				_prb.abort(m1.getInt(DMT.REASON), m1.getString(DMT.DESCRIPTION));
				throw new RetrievalException(m1.getInt(DMT.REASON), m1.getString(DMT.DESCRIPTION));
			}
			if ((m1 != null) && (m1.getSpec().equals(DMT.packetTransmit))) {
				consecutiveMissingPacketReports = 0;
				// packetTransmit received
				int packetNo = m1.getInt(DMT.PACKET_NO);
				BitArray sent = (BitArray) m1.getObject(DMT.SENT);
				Buffer data = (Buffer) m1.getObject(DMT.DATA);
				_prb.addPacket(packetNo, data);
				// Remove it from rrmp if its in there
				_recentlyReportedMissingPackets.remove(new Integer(packetNo));
				// Check that we have what the sender thinks we have
				LinkedList missing = new LinkedList();
				for (int x = 0; x < sent.getSize(); x++) {
					if (sent.bitAt(x) && !_prb.isReceived(x)) {
						// Sender thinks we have a block which we don't, but have we already
						// re-requested it recently?
						Long resendTime = (Long) _recentlyReportedMissingPackets.get(new Integer(x));
						if ((resendTime == null) || (System.currentTimeMillis() > resendTime.longValue())) {
							// Make a note of the earliest time we should resend this, based on the number of other
							// packets we are already waiting for
							long resendWait = System.currentTimeMillis()
									+ (MAX_ROUND_TRIP_TIME + (_recentlyReportedMissingPackets.size() * MAX_SEND_INTERVAL));
							_recentlyReportedMissingPackets.put(new Integer(x), (new Long(resendWait)));
							missing.add(new Integer(x));
						}
					}
				}
				if(Logger.shouldLog(Logger.MINOR, this))
					Logger.minor(this, "Missing: "+missing.size());
				if (missing.size() > 0) {
					Message mn = DMT.createMissingPacketNotification(_uid, missing);
					_usm.send(_sender, mn, _ctr);
					consecutiveMissingPacketReports++;
					if (missing.size() > 50) {
						Logger.normal(this, "Excessive packet loss : "+mn);
					}
				}

			}
			if ((m1 == null) || (m1.getSpec().equals(DMT.allSent))) {
				if (consecutiveMissingPacketReports >= MAX_CONSECUTIVE_MISSING_PACKET_REPORTS) {
					_prb.abort(RetrievalException.SENDER_DIED, "Sender unresponsive to resend requests");
					throw new RetrievalException(RetrievalException.SENDER_DIED,
							"Sender unresponsive to resend requests");
				}
				LinkedList missing = new LinkedList();
				for (int x = 0; x < _prb.getNumPackets(); x++) {
					if (!_prb.isReceived(x)) {
						missing.add(new Integer(x));
					}
				}
				Message mn = DMT.createMissingPacketNotification(_uid, missing);
				_usm.send(_sender, mn, _ctr);
				consecutiveMissingPacketReports++;
				if (missing.size() > 50) {
					Logger.normal(this, "Sending large missingPacketNotification due to packet receiver timeout after "+RECEIPT_TIMEOUT+"ms");
				}
			}
		}
		_usm.send(_sender, DMT.createAllReceived(_uid), _ctr);
		return _prb.getBlock();
		} catch(NotConnectedException e) {
		    throw new RetrievalException(RetrievalException.SENDER_DISCONNECTED);
		} catch(AbortedException e) {
			// We didn't cause it?!
			Logger.error(this, "Caught in receive - probably a bug as receive sets it: "+e);
			throw new RetrievalException(RetrievalException.UNKNOWN);
		}
	}
}
