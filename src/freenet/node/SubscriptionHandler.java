package freenet.node;

import java.util.LinkedList;

import freenet.keys.PublishStreamKey;
import freenet.support.Logger;
import freenet.support.NumberedItem;
import freenet.support.NumberedRecentItems;

/**
 * A single subscription.
 * May have a parent node, or may be the root.
 * May have many child nodes.
 */
public class SubscriptionHandler {
    
    static final int KEEP_PACKETS = 32;
    
    final PublishStreamKey key;
    ClientSubscriptionHandler localSubscribers;
    final LinkedList subscriberPeers;
    final NumberedRecentItems packets;

    public SubscriptionHandler(PublishStreamKey k) {
        key = k;
        subscriberPeers = new LinkedList();
        packets = new NumberedRecentItems(KEEP_PACKETS, true);
    }

    /**
     * Set the local subscribers handler.
     * @return The previous local subscribers handler - should be null!
     */
    public synchronized ClientSubscriptionHandler setLocal(ClientSubscriptionHandler csh) {
        ClientSubscriptionHandler h = localSubscribers;
        localSubscribers = csh;
        return h;
    }

    public synchronized boolean shouldDrop() {
        return (subscriberPeers.isEmpty() && localSubscribers == null);
    }

    /**
     * Process an incoming PublishData packet.
     */
    public void processPacket(long packetNumber, byte[] packetData, PeerNode source) {
        // First, have we seen it before?
        if(!packets.add(new PacketItem(packetNumber, packetData))) {
            Logger.minor(this, "Got packet "+packetNumber+" on stream "+key+" twice");
            return;
        }
        // FIXME: redistribute it to node subscribers
        // Redistribute it to local subscribers
        localSubscribers.processPacket(packetNumber, packetData);
    }

    public class PacketItem implements NumberedItem {

        long packetNumber;
        byte[] data;
        
        public PacketItem(long packetNumber, byte[] packetData) {
            this.packetNumber = packetNumber;
            data = packetData;
        }

        public long getNumber() {
            return packetNumber;
        }

    }

}
