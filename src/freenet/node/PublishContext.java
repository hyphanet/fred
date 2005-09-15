package freenet.node;

import freenet.keys.ClientPublishStreamKey;

/**
 * Context for a locally originated publish stream.
 */
public class PublishContext {
    
    final ClientPublishStreamKey key;
    final Node node;
    /** Last packet number. Incremented each time, modulo 2^63-1 */
    private long lastPacketNumber;
    
    /**
     * @param key
     */
    public PublishContext(ClientPublishStreamKey key, Node node) {
        this.key = key;
        this.node = node;
        lastPacketNumber = Math.abs(node.random.nextLong());
    }

    /**
     * Publish a block of data to the stream. Must not exceed
     * size limit.
     */
    public void publish(byte[] data) {
        long packetNumber;
        synchronized(this) {
            packetNumber = lastPacketNumber;
            long next = lastPacketNumber+1;
            if(next < 0) next = 0;
            lastPacketNumber = next;
        }
        byte[] encrypted = 
            key.encrypt(packetNumber, data, node.random);
        PublishHandlerSender ps = new PublishHandlerSender(key.getKey(), node.random.nextLong(), packetNumber, encrypted, node, null, -1.0);
    }

}
