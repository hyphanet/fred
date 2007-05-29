/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.io.xfer;

import freenet.io.comm.PeerContext;

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

	public BulkTransmitter(PartiallyReceivedBulk prb, PeerContext peer, long uid) {
		this.prb = prb;
		this.peer = peer;
		this.uid = uid;
	}
}
