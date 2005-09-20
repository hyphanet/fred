package freenet.node;

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
    PeerNode[] subscriberPeers;
    final NumberedRecentItems packets;

    public SubscriptionHandler(PublishStreamKey k) {
        key = k;
        subscriberPeers = null;
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
        return subscriberPeersEmpty() && localSubscribers == null;
    }

    /**
     * @return True if there are no subscriber peers.
     */
    private final boolean subscriberPeersEmpty() {
        PeerNode[] peers = subscriberPeers;
        return (peers == null || peers.length == 0);
    }

    public void addSubscriberNode(PeerNode pn, long lastSeen) {
        synchronized(this) {
            if(subscriberPeers == null || subscriberPeers.length == 0) {
                subscriberPeers = new PeerNode[] { pn };
            } else {
                PeerNode[] peers = new PeerNode[subscriberPeers.length+1];
                System.arraycopy(subscriberPeers, 0, peers, 0, subscriberPeers.length);
                peers[peers.length-1] = pn;
                subscriberPeers = peers;
            }
        }
        NumberedItem[] items = packets.getAfter(lastSeen);
        if(items == null || items.length == 0) return;
        for(int i=0;i<items.length;i++) {
            PacketItem item = (PacketItem)items[i];
            item.forwardTo(pn);
        }
    }
    
    /**
     * Process an incoming PublishData packet.
     */
    public void processPacket(long packetNumber, byte[] packetData, PeerNode source) {
        // First, have we seen it before?
        PacketItem item = new PacketItem(packetNumber, packetData);
        if(!packets.add(item)) {
            Logger.minor(this, "Got packet "+packetNumber+" on stream "+key+" twice");
            return;
        }
        PeerNode[] peers;
        // We don't strictly need to synchronize, but
        // if we don't we may lose packets.
        synchronized(this) {
            peers = subscriberPeers;
        }
        if(peers != null)
            for(int i=0;i<peers.length;i++)
                item.forwardTo(peers[i]);
        
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

        /**
         * Forward this packet to a subscriber node.
         * As an FNPSubscribeData. NOT an FNPPublishData.
         * DO NOT CALL WITH LOCKS HELD, as sends packets.
         * @param pn Node to send to.
         */
        public void forwardTo(PeerNode pn) {
            
            // TODO Auto-generated method stub
            
        }

        public long getNumber() {
            return packetNumber;
        }

    }

}
