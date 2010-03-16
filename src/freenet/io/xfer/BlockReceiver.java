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

import freenet.io.comm.AsyncMessageFilterCallback;
import freenet.io.comm.ByteCounter;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageCore;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.PeerContext;
import freenet.io.comm.RetrievalException;
import freenet.node.Ticker;
import freenet.support.BitArray;
import freenet.support.Buffer;
import freenet.support.Logger;
import freenet.support.math.MedianMeanRunningAverage;

/**
 * @author ian
 */
public class BlockReceiver implements AsyncMessageFilterCallback {

	/*
	 * RECEIPT_TIMEOUT must be less than 60 seconds because BlockTransmitter times out after not
	 * hearing from us in 60 seconds. Without contact from the transmitter, we will try sending
	 * at most MAX_CONSECUTIVE_MISSING_PACKET_REPORTS every RECEIPT_TIMEOUT to recover.
	 */
	public static final int RECEIPT_TIMEOUT = 30000;
	// TODO: This should be proportional to the calculated round-trip-time, not a constant
	public static final int MAX_ROUND_TRIP_TIME = RECEIPT_TIMEOUT;
	public static final int MAX_CONSECUTIVE_MISSING_PACKET_REPORTS = 4;
	public static final int MAX_SEND_INTERVAL = 500;
	public static final int CLEANUP_TIMEOUT = 5000;
	// After 15 seconds, the receive is overdue and will cause backoff.
	public static final int TOO_LONG_TIMEOUT = 15000;
	PartiallyReceivedBlock _prb;
	PeerContext _sender;
	long _uid;
	MessageCore _usm;
	/** packet : Integer -> reportTime : Long * */
	HashMap<Integer, Long> _recentlyReportedMissingPackets = new HashMap<Integer, Long>();
	ByteCounter _ctr;
	Ticker _ticker;
	boolean sentAborted;
	private MessageFilter discardFilter;
	private long discardEndTime;
	private boolean senderAborted;
//	private final boolean _doTooLong;

	boolean logMINOR=Logger.shouldLog(Logger.MINOR, this);

	public BlockReceiver(MessageCore usm, PeerContext sender, long uid, PartiallyReceivedBlock prb, ByteCounter ctr, Ticker ticker, boolean doTooLong) {
		_sender = sender;
		_prb = prb;
		_uid = uid;
		_usm = usm;
		_ctr = ctr;
		_ticker = ticker;
//		_doTooLong = doTooLong;
	}

	public void sendAborted(int reason, String desc) throws NotConnectedException {
		_usm.send(_sender, DMT.createSendAborted(_uid, reason, desc), _ctr);
		sentAborted=true;
	}

	public byte[] receive() throws RetrievalException {
		long startTime = System.currentTimeMillis();
//		if(_doTooLong) {
//		_ticker.queueTimedJob(new Runnable() {
//
//			public void run() {
//				if(!_sender.isConnected()) return;
//				try {
//					if(_prb.allReceived()) return;
//				} catch (AbortedException e) {
//					return;
//				}
//				Logger.error(this, "Transfer took too long: "+_uid+" from "+_sender);
//				synchronized(BlockReceiver.this) {
//					tookTooLong = true;
//				}
//				_sender.transferFailed("Took too long (still running)");
//			}
//			
//		}, TOO_LONG_TIMEOUT);
//		}
		int consecutiveMissingPacketReports = 0;
		try {
			MessageFilter mfPacketTransmit = MessageFilter.create().setTimeout(RECEIPT_TIMEOUT).setType(DMT.packetTransmit).setField(DMT.UID, _uid).setSource(_sender);
			MessageFilter mfAllSent = MessageFilter.create().setTimeout(RECEIPT_TIMEOUT).setType(DMT.allSent).setField(DMT.UID, _uid).setSource(_sender);
			MessageFilter mfSendAborted = MessageFilter.create().setTimeout(RECEIPT_TIMEOUT).setType(DMT.sendAborted).setField(DMT.UID, _uid).setSource(_sender);
			MessageFilter relevantMessages=mfPacketTransmit.or(mfAllSent.or(mfSendAborted));
		while (!_prb.allReceived()) {
			Message m1;
			try {
				m1 = _usm.waitFor(relevantMessages, _ctr);
				if(!_sender.isConnected()) throw new DisconnectedException();
			} catch (DisconnectedException e1) {
				Logger.normal(this, "Disconnected during receive: "+_uid+" from "+_sender);
				_prb.abort(RetrievalException.SENDER_DISCONNECTED, "Disconnected during receive");
				throw new RetrievalException(RetrievalException.SENDER_DISCONNECTED);
			}
			if(logMINOR) {
				Logger.minor(this, "Received "+m1);
			}
			if ((m1 != null) && m1.getSpec().equals(DMT.sendAborted)) {
				String desc=m1.getString(DMT.DESCRIPTION);
				if (desc.indexOf("Upstream")<0) {
					desc="Upstream transmit error: "+desc;
				}
				_prb.abort(m1.getInt(DMT.REASON), desc);
				synchronized(this) {
					senderAborted = true;
				}
				throw new RetrievalException(m1.getInt(DMT.REASON), desc);
			}
			if ((m1 != null) && (m1.getSpec().equals(DMT.packetTransmit))) {
				consecutiveMissingPacketReports = 0;
				// packetTransmit received
				int packetNo = m1.getInt(DMT.PACKET_NO);
				BitArray sent = (BitArray) m1.getObject(DMT.SENT);
				Buffer data = (Buffer) m1.getObject(DMT.DATA);
				_prb.addPacket(packetNo, data);
				// Remove it from rrmp if its in there
				_recentlyReportedMissingPackets.remove(packetNo);
				// Check that we have what the sender thinks we have
				LinkedList<Integer> missing = new LinkedList<Integer>();
				for (int x = 0; x < sent.getSize(); x++) {
					if (sent.bitAt(x) && !_prb.isReceived(x)) {
						// Sender thinks we have a block which we don't, but have we already
						// re-requested it recently?
						Long resendTime = _recentlyReportedMissingPackets.get(x);
						if ((resendTime == null) || (System.currentTimeMillis() > resendTime.longValue())) {
							// Make a note of the earliest time we should resend this, based on the number of other
							// packets we are already waiting for
							long resendWait = System.currentTimeMillis()
									+ (MAX_ROUND_TRIP_TIME + (_recentlyReportedMissingPackets.size() * MAX_SEND_INTERVAL));
							_recentlyReportedMissingPackets.put(x, resendWait);
							missing.add(x);
						}
					}
				}
				if(logMINOR) {
					Logger.minor(this, "Missing: "+missing.size());
				}
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
				LinkedList<Integer> missing = new LinkedList<Integer>();
				for (int x = 0; x < _prb.getNumPackets(); x++) {
					if (!_prb.isReceived(x)) {
						missing.add(x);
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
		discardEndTime=System.currentTimeMillis()+CLEANUP_TIMEOUT;
		discardFilter=relevantMessages;
		maybeResetDiscardFilter();
		long endTime = System.currentTimeMillis();
		long transferTime = (endTime - startTime);
		if(logMINOR) {
			synchronized(avgTimeTaken) {
				avgTimeTaken.report(transferTime);
				Logger.minor(this, "Block transfer took "+transferTime+"ms - average is "+avgTimeTaken);
			}
		}

		return _prb.getBlock();
		} catch(NotConnectedException e) {
			throw new RetrievalException(RetrievalException.SENDER_DISCONNECTED);
		} catch(AbortedException e) {
			// We didn't cause it?!
			Logger.error(this, "Caught in receive - probably a bug as receive sets it: "+e);
			throw new RetrievalException(RetrievalException.UNKNOWN, "Aborted?");
		} finally {
			try {
				if (_prb.isAborted() && !sentAborted) {
					sendAborted(_prb.getAbortReason(), _prb.getAbortDescription());
				}
			} catch (NotConnectedException e) {
				//ignore
			}
		}
	}

	private static final MedianMeanRunningAverage avgTimeTaken = new MedianMeanRunningAverage();

	private void maybeResetDiscardFilter() {
		long timeleft=discardEndTime-System.currentTimeMillis();
		if (timeleft>0) {
			try {
				discardFilter.setTimeout((int)timeleft);
				_usm.addAsyncFilter(discardFilter, this);
			} catch (DisconnectedException e) {
				//ignore
			}
		}
	}

	/**
	 * Used to discard leftover messages, usually just packetTransmit and allSent.
	 * allSent, is quite common, as the receive() routine usually quits immeadiately on receiving all packets.
	 * packetTransmit is less common, when receive() requested what it thought was a missing packet, only reordered.
	 */
	public void onMatched(Message m) {
		if (logMINOR)
			Logger.minor(this, "discarding message post-receive: "+m);
		maybeResetDiscardFilter();
	}

	public boolean shouldTimeout() {
		return false;
	}

	public void onTimeout() {
		//ignore
	}

	public void onDisconnect(PeerContext ctx) {
		// Ignore
	}

	public void onRestarted(PeerContext ctx) {
		// Ignore
	}

	public synchronized boolean senderAborted() {
		return senderAborted;
	}
}
