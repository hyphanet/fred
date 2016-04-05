package freenet.node.simulator;

import java.util.HashMap;

import freenet.node.Node;
import freenet.node.UIDTag;

/** Counts all requests running on each node, while inheriting the parent functionality for
 * tracking the number of requests across all nodes. */
public class LocalRequestUIDsCounter extends TotalRequestUIDsCounter {

    public class NodeStats implements Cloneable {
        long runningRequests;
        long runningLocalRequests;
        long totalRequests;
        long totalLocalRequests;
        public NodeStats clone() {
            try {
                return (NodeStats) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new Error(e); // Impossible
            }
        }
    }
    
    private final HashMap<Node, NodeStats> statsByNode = new HashMap<Node, NodeStats>();
    
    private NodeStats makeStats(Node node) {
        NodeStats stats = statsByNode.get(node);
        if(stats == null) {
            stats = new NodeStats();
            statsByNode.put(node, stats);
        }
        return stats;
    }
    
    @Override
    public synchronized void onLock(UIDTag tag, Node node) {
        NodeStats stats = makeStats(node);
        stats.runningRequests++;
        stats.totalRequests++;
        if(tag.isLocal()) {
            stats.runningLocalRequests++;
            stats.totalLocalRequests++;
        }
        super.onLock(tag, node);
    }

    @Override
    public synchronized void onUnlock(UIDTag tag, Node node) {
        NodeStats stats = makeStats(node);
        stats.runningRequests--;
        if(tag.isLocal()) {
            stats.runningLocalRequests--;
        }
        super.onUnlock(tag, node);
    }
    
    public synchronized NodeStats getStats(Node node) {
        NodeStats stats = makeStats(node);
        return stats.clone();
    }
    
}
