/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.io.xfer;

import freenet.io.comm.PeerContext;

/**
 * Bulk (not block) data transfer - receiver class. Bulk transfer is designed for largish files, much
 * larger than blocks, where we have the whole file at the outset.
 * @author toad
 */
public class BulkReceiver {
	
	/** Tracks the data we have received */
	final PartiallyReceivedBulk prb;
	/** Peer we are receiving from */
	final PeerContext peer;
	/** Transfer UID for messages */
	final long uid;

	public BulkReceiver(PartiallyReceivedBulk prb, PeerContext peer, long uid) {
		this.prb = prb;
		this.peer = peer;
		this.uid = uid;
	}
}
