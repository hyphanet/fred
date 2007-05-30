/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.io.xfer;

import freenet.io.comm.PeerContext;
import freenet.support.BitArray;

/**
 * Bulk data transfer (not block). Bulk transfer is designed for files which may be much bigger than a 
 * key block, and where we have the whole file at the outset. 
 * 
 * Used by update over mandatory, sending a file to our peers attached to an N2NTM etc.
 * @author toad
 */
public class BulkTransmitter {

	/** Available blocks */
	final PartiallyReceivedBulk prb;
	/** Peer who we are sending the data to */
	final PeerContext peer;
	/** Transfer UID for messages */
	final long uid;
	/** Blocks we have but haven't sent yet. 0 = block sent or not present, 1 = block present but not sent */
	final BitArray blocksNotSentButPresent;

	public BulkTransmitter(PartiallyReceivedBulk prb, PeerContext peer, long uid) {
		this.prb = prb;
		this.peer = peer;
		this.uid = uid;
		// Need to sync on prb while doing both operations, to avoid race condition.
		// Specifically, we must not get calls to blockReceived() until blocksNotSentButPresent
		// has been set, AND it must be accurate, so there must not be an unlocked period
		// between cloning and adding.
		synchronized(prb) {
			// We can just clone it.
			blocksNotSentButPresent = prb.cloneBlocksReceived();
			prb.add(this);
		}
	}

	/**
	 * Received a block. Set the relevant bit to 1 to indicate that we have the block but haven't sent
	 * it yet. **Only called by PartiallyReceivedBulk.**
	 * @param block The block number that has been received.
	 */
	synchronized void blockReceived(int block) {
		blocksNotSentButPresent.setBit(block, true);
	}

	/**
	 * Called if the transfer fails because of a throwable.
	 * @param t The throwable causing the failure.
	 */
	void fail(Throwable t) {
		// TODO Auto-generated method stub
	}
}
