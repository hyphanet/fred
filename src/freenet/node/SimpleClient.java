package freenet.node;

import freenet.keys.ClientCHK;
import freenet.keys.ClientCHKBlock;
import freenet.keys.ClientPublishStreamKey;

/**
 * @author amphibian
 *
 * Simple client interface... fetch and push single CHKs. No
 * splitfile decoding, no DBRs, no SSKs, for now.
 * 
 * We can build higher layers on top of this.
 */
public interface SimpleClient {

    /**
     * Fetch a key. Return null if cannot retrieve it.
     */
    public ClientCHKBlock getCHK(ClientCHK key);

    /**
     * Insert a key.
     */
    public void putCHK(ClientCHKBlock key);

    /**
     * Create a publish/subscribe stream key. Create any context
     * needed to insert it.
     */
    public ClientPublishStreamKey createPublishStream();
    
    /**
     * Publish a block of data to a publish/subscribe key.
     */
    public void publish(ClientPublishStreamKey key, byte[] data);

    /**
     * Subscribe to a stream.
     * @param key The stream key.
     * @param cb Callback to notify when we get data.
     * @return True if the subscribe succeeded.
     */
    public ClientSubscription subscribe(ClientPublishStreamKey key, SubscriptionCallback cb);
}
