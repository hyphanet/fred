/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.transport;

import freenet.io.comm.AsyncMessageCallback;
import freenet.node.PeerNode;

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
    public final PeerNode pn;
    public final PacketTracker kt;
    public final byte[] buf;
    public final int packetNumber;
    public final AsyncMessageCallback[] callbacks;
    public final short priority;
}