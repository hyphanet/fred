/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.io.comm;

/**
 * Filter interface used by Freenet to decrypt incoming UDP packets.
 * @see freenet.node.OutgoingPacketMangler
 */
public interface IncomingPacketFilter {

	public enum DECODED {
		DECODED,
		NOT_DECODED,
		DIDNT_WANT_OPENNET,
		SHUTTING_DOWN
	}
	
    /**
     * Process an incoming packet. This method should call
     * USM.decodePacket() and USM.checkFilters() if necessary to 
     * decode and dispatch messages.
     * @param buf The buffer to read from. Note that this may be reused later on; any
     * data to keep must be copied.
     * @param offset The offset to start reading from.
     * @param length The length in bytes to read.
     * @param peer The peer which sent us the packet. We only know
     * the Peer because it's incoming; we are supposed to create
     * or find PeerContext's for the Message's.
     * @param now The exact time at which the packet was received.
     * @return 
     */
	DECODED process(byte[] buf, int offset, int length, Peer peer, long now);

    // Outgoing packets are handled elsewhere...
    
    /**
     * Is the given connection closed?
     */
    boolean isDisconnected(PeerContext context);
}
