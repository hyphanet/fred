package freenet.node;

import java.util.HashMap;

import freenet.keys.ClientPublishStreamKey;
import freenet.keys.PublishStreamKey;
import freenet.support.Logger;

/**
 * Tracks Publish/Subscribe streams:
 * - Local subscriptions.
 * - Remote subscriptions (other nodes subscribing to a stream through us).
 * - Whether we are root for a given stream.
 * - Nodes that we subscribe through to get a given stream (i.e. our tree parent).
 */
public class SubscriptionManager {
    
    // We have up to 32 subscriptions
    private final int MAX_COUNT = 32;
    
    private final Node node;
    /** map: key -> sub. definitively has all subs. */
    private final HashMap subscriptionsByKey;
//    /** map: parent node -> linkedlist of subs. does not include parent==null i.e. parent==this. */
//    private final HashMap subscriptionsByParent;
//    /** map: child node -> linkedlist of subs. some subs may have no children so won't be included. */
//    private final HashMap subscriptionsByChildren;
    /** Client subscriptions. These are the ClientSubscriptionHandler's. */
    private final HashMap clientSubscriptionsByKey;
    
    SubscriptionManager(Node n) {
        node = n;
        subscriptionsByKey = new HashMap();
//        subscriptionsByParent = new HashMap();
//        subscriptionsByChildren = new HashMap();
        clientSubscriptionsByKey = new HashMap();
    }
    
    /**
     * Local subscription.
     * Add the stream if necessary, and subscribe to it.
     * @return Null if cannot subscribe, otherwise the ClientSubscription.
     */
    public ClientSubscription localSubscribe(ClientPublishStreamKey key, SubscriptionCallback cb) {
        // FIXME implement doing a sub request
        // For now we will just eavesdrop on locally passed subs.
        // Each pretends it is the root
        ClientSubscriptionHandler csh;
        boolean add = false;
        synchronized(this) {
            csh = (ClientSubscriptionHandler) clientSubscriptionsByKey.get(key);
            if(csh == null) {
                csh = new ClientSubscriptionHandler(this, key);
                add = true;
                clientSubscriptionsByKey.put(key, csh);
            }
        }
        if(add) {
            Logger.minor(this, "New subscription to "+key);
            SubscriptionHandler sub = makeSubscription(key.getKey());
            if(sub.setLocal(csh) != null) {
                Logger.error(this, "Had local already! for "+key);
            }
        }
        ClientSubscriptionImpl csi = new ClientSubscriptionImpl(key, cb, csh);
        csh.add(csi);
        // FIXME implement the rest - especially sending sub reqs out
        return csi;
    }

    /**
     * Create a back-end subscription. This can be called
     * as a result of a subscribe request or of a local
     * subscription.
     */
    private synchronized SubscriptionHandler makeSubscription(PublishStreamKey key) {
        SubscriptionHandler sub = (SubscriptionHandler) subscriptionsByKey.get(key);
        if(sub != null) return sub;
        if(subscriptionsByKey.size() >= MAX_COUNT) {
            Logger.normal(this, "Rejecting subscription for "+key);
            return null;
        }
        
        // Make a new one
        sub = new SubscriptionHandler(key);
        subscriptionsByKey.put(key, sub);
        return sub;
    }

    public synchronized void remove(ClientSubscriptionHandler handler) {
        PublishStreamKey key = handler.getKey().getKey();
        SubscriptionHandler sub = (SubscriptionHandler) subscriptionsByKey.get(key);
        ClientSubscriptionHandler oldHandler = sub.setLocal(handler);
        if(oldHandler != null && oldHandler != handler) {
            Logger.error(this, "Already had a different handler: "+oldHandler+" should be "+handler);
        }
        if(sub.shouldDrop()) {
            drop(sub, key);
        }
    }

    private synchronized void drop(SubscriptionHandler sub, PublishStreamKey key) {
        subscriptionsByKey.remove(key);
    }

    /**
     * Handle a received packet.
     */
    public void receivedPacket(PublishStreamKey key, long packetNumber, byte[] packetData, PeerNode source) {
        SubscriptionHandler sub;
        synchronized(this) {
            sub = (SubscriptionHandler) subscriptionsByKey.get(key);
        }
        if(sub == null) {
            Logger.normal(this, "Dropped sub packet from "+source+" on "+key);
            return;
        }
        sub.processPacket(packetNumber, packetData, source);
    }
}
