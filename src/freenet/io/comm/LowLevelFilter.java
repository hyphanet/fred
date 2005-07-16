package freenet.io.comm;

/**
 * Filter interface used by Freenet to decrypt incoming packets.
 */
public interface LowLevelFilter {

    /**
     * Process an incoming packet. This method should call
     * USM.decodePacket() and USM.checkFilters() if necessary to 
     * decode and dispatch messages.
     * @param buf The buffer to read from.
     * @param offset The offset to start reading from.
     * @param length The length in bytes to read.
     * @param peer The peer which sent us the packet.
     */
    void process(byte[] buf, int offset, int length, Peer peer);

    /**
     * Process an outgoing packet. Takes a byte[], returns
     * a byte[]. This is then sent in the usual way.
     * @param buf The buffer to read from.
     * @param offset The offset to start reading from.
     * @param length The length in bytes to read.
     * @param peer The peer the message will be sent to.
     */
    byte[] processOutgoing(byte[] buf, int offset, int length, Peer peer);
}
