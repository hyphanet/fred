package freenet.node;

import freenet.keys.ClientPublishStreamKey;

/**
 * A client subscription.
 */
public interface ClientSubscription {

    public ClientPublishStreamKey getKey();
    
    public SubscriptionCallback getCallback();
    
    public void disconnect();
    
}
