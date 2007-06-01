/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.io.xfer;

import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.PeerContext;
import freenet.io.comm.RetrievalException;
import freenet.support.ShortBuffer;

/**
 * Bulk (not block) data transfer - receiver class. Bulk transfer is designed for largish files, much
 * larger than blocks, where we have the whole file at the outset.
 * @author toad
 */
public class BulkReceiver {

	static final int TIMEOUT = 5*60*1000;
	/** Tracks the data we have received */
	final PartiallyReceivedBulk prb;
	/** Peer we are receiving from */
	final PeerContext peer;
	/** Transfer UID for messages */
	final long uid;
	private boolean sentCancel;
	/** Not persistent over reboots */
	final long peerBootID;

	public BulkReceiver(PartiallyReceivedBulk prb, PeerContext peer, long uid) {
		this.prb = prb;
		this.peer = peer;
		this.uid = uid;
		this.peerBootID = peer.getBootID();
	}

	public void onAborted() {
		synchronized(this) {
			if(sentCancel) return;
			sentCancel = true;
		}
		try {
			peer.sendAsync(DMT.createFNPBulkReceiveAborted(uid), null, 0, null);
		} catch (NotConnectedException e) {
			// Cool
		}
	}

	/**
	 * Receive the file.
	 * @return True if the whole file was received, false otherwise.
	 */
	public boolean receive() {
		MessageFilter mfSendKilled = MessageFilter.create().setSource(peer).setType(DMT.FNPBulkSendAborted) .setField(DMT.UID, uid).setTimeout(TIMEOUT);
		MessageFilter mfPacket = MessageFilter.create().setSource(peer).setType(DMT.FNPBulkPacketSend) .setField(DMT.UID, uid).setTimeout(TIMEOUT);
		while(true) {
			if(prb.hasWholeFile()) return true;
			Message m;
			try {
				m = prb.usm.waitFor(mfSendKilled.or(mfPacket), null);
			} catch (DisconnectedException e) {
				prb.abort(RetrievalException.SENDER_DISCONNECTED, "Sender disconnected");
				return false;
			}
			if(peer.getBootID() != peerBootID) {
				prb.abort(RetrievalException.SENDER_DIED, "Sender restarted");
				return false;
			}
			if(m == null) {
				prb.abort(RetrievalException.TIMED_OUT, "Sender timeout");
				return false;
			}
			if(m.getSpec() == DMT.FNPBulkSendAborted) {
				prb.abort(RetrievalException.SENDER_DIED, "Sender cancelled send");
				return false;
			}
			if(m.getSpec() == DMT.FNPBulkPacketSend) {
				int packetNo = m.getInt(DMT.PACKET_NO);
				byte[] data = ((ShortBuffer) m.getObject(DMT.DATA)).getData();
				prb.received(packetNo, data, 0, data.length);
			}
		}
	}
}
