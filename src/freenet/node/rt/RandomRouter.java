package freenet.node.rt;

import java.util.HashSet;

import freenet.crypt.RandomSource;
import freenet.keys.Key;
import freenet.node.PeerManager;
import freenet.node.PeerNode;
import freenet.node.rt.*;

/**
 * Router implementation that routes entirely randomly. 
 */
public class RandomRouter implements Router {

    final PeerManager pm;
    final RandomSource random;

    public RandomRouter(PeerManager manager, RandomSource r) {
        this.pm = manager;
        this.random = r;
    }

    public Routing route(Key k, int hopsToLive) {
        return new RandomRouting();
    }

    public void returnRouting(Routing r) {
        // Don't recycle
    }

    /**
     * Routing impl that routes entirely randomly.
     */
    public class RandomRouting implements Routing {

        // Need to ensure we don't route to the same node twice.
        HashSet nodesRoutedTo = new HashSet();

        public PeerNode getNextNode() {
            PeerNode[] nodes = pm.getConnectedNodesArray();
            if(nodes.length <= nodesRoutedTo.size())
                return null; // probably no more nodes to try
            
            while(true) {
                PeerNode p = nodes[random.nextInt(nodes.length)];
                if(!nodesRoutedTo.contains(p)) {
                    return p;
                }
            }
        }

    }
    
}
