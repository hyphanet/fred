package freenet.node;

/**
 * Publish/Subscribe subscriber callback.
 */
public interface SubscriptionCallback {

    void got(long packetNumber, byte[] data);
    
    void lostConnection();
    
    void restarted();
    
    void connected();
    
}
