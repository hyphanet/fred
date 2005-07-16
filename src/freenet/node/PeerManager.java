package freenet.node;

import freenet.io.comm.Peer;

/**
 * @author amphibian
 * 
 * Maintains:
 * - A list of peers we want to connect to.
 * - A list of peers we are actually connected to.
 * - Each peer's Location.
 */
public class PeerManager {
    
    /** All the peers we want to connect to */
    NodePeer[] myPeers;
    
    /** All the peers we are actually connected to */
    NodePeer[] connectedPeers;
    
    NodePeer route(double targetLocation, RoutingContext ctx) {
        double minDist = 1.1;
        NodePeer best = null;
        for(int i=0;i<connectedPeers.length;i++) {
            NodePeer p = connectedPeers[i];
            if(ctx.alreadyRoutedTo(p)) continue;
            double loc = p.getLocation().getValue();
            double dist = Math.abs(loc - targetLocation);
            if(dist < minDist) {
                minDist = dist;
                best = p;
            }
        }
        return best;
    }
    
    NodePeer route(Location target, RoutingContext ctx) {
        return route(target.getValue(), ctx);
    }

    /**
     * Find the node with the given Peer address.
     */
    public NodePeer getByPeer(Peer peer) {
        for(int i=0;i<myPeers.length;i++) {
            if(myPeers[i].getPeer().equals(peer))
                return myPeers[i];
        }
        return null;
    }
}
