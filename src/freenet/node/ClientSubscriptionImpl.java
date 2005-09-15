package freenet.node;

import freenet.keys.ClientPublishStreamKey;
import freenet.support.Logger;

public class ClientSubscriptionImpl implements ClientSubscription {

    private ClientPublishStreamKey key;
    private SubscriptionCallback cb;
    private ClientSubscriptionHandler ch;
    private boolean finished = false;
    
    /**
     * @param key2
     * @param cb2
     * @param csh
     */
    public ClientSubscriptionImpl(ClientPublishStreamKey key2, SubscriptionCallback cb2, ClientSubscriptionHandler csh) {
        cb = cb2;
        key = key2;
        ch = csh;
    }

    public ClientPublishStreamKey getKey() {
        return key;
    }

    public SubscriptionCallback getCallback() {
        return cb;
    }

    public void disconnect() {
        finished = true;
        ch.remove(this);
    }

    /**
     * Received decrypted data from the stream.
     */
    public void processPacket(long packetNumber, byte[] finalData) {
        try {
            cb.got(packetNumber, finalData);
        } catch (Throwable t) {
            Logger.error(this, "Caught "+t+" from callback in processPacket("+
                    packetNumber+") on "+this, t);
        }
    }

}
