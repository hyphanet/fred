package freenet.io.comm;

/**
 * Filter interface used by Freenet to decrypt incoming packets.
 */
public interface LowLevelFilter {

    /**
     * Process an incoming packet. This method should call
     * USM.checkFilters() if necessary to dispatch a message.
     * @param buf The buffer to read from.
     * @param offset The offset to start reading from.
     * @param length The length in bytes to read.
     * @param peer The peer which sent us the packet.
     */
    void process(byte[] buf, int offset, int length, Peer peer);

}
