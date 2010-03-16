/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.io.comm.AsyncMessageCallback;

/**
 * A packet to be resent. Includes a packet number, and the
 * message as byte[].
 */
public class ResendPacketItem {
	public ResendPacketItem(byte[] payload, int packetNumber, PacketTracker k, AsyncMessageCallback[] callbacks, short priority) {
		pn = k.pn;
		kt = k;
		buf = payload;
		this.packetNumber = packetNumber;
		this.callbacks = callbacks;
		this.priority = priority;
	}
	final PeerNode pn;
	final PacketTracker kt;
	final byte[] buf;
	final int packetNumber;
	final AsyncMessageCallback[] callbacks;
	final short priority;
}