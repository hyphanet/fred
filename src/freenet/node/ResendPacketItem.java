package freenet.node;

/**
 * A packet to be resent. Includes a packet number, and the 
 * message as byte[].
 */
class ResendPacketItem {
    public ResendPacketItem(byte[] payload, int packetNumber, KeyTracker k, AsyncMessageCallback[] callbacks) {
        pn = k.pn;
        kt = k;
        buf = payload;
        this.packetNumber = packetNumber;
        this.callbacks = callbacks;
    }
    final PeerNode pn;
    final KeyTracker kt;
    final byte[] buf;
    final int packetNumber;
    final AsyncMessageCallback[] callbacks;        
}