package freenet.node.simulator;

import java.util.HashMap;

import freenet.node.Node;
import freenet.node.UIDTag;
import freenet.support.math.TimeRunningAverage;

/** Counts all requests running on each node, while inheriting the parent functionality for
 * tracking the number of requests across all nodes. */
public class LocalRequestUIDsCounter extends TotalRequestUIDsCounter {

    public class NodeStatsSnapshot {
        long runningRequests;
        long runningLocalRequests;
        long totalRequests;
        long totalLocalRequests;
        double averageRunningRequests;
        double averageRunningLocalRequests;
    }
    
    private class NodeStats implements Cloneable {
        long runningRequests;
        long runningLocalRequests;
        long totalRequests;
        long totalLocalRequests;
        final TimeRunningAverage runningRequestsAverage = new TimeRunningAverage();
        final TimeRunningAverage runningLocalRequestsAverage = new TimeRunningAverage();
        public NodeStatsSnapshot snapshot() {
            NodeStatsSnapshot snapshot = new NodeStatsSnapshot();
            snapshot.runningLocalRequests = runningLocalRequests;
            snapshot.runningRequests = runningRequests;
            snapshot.totalLocalRequests = totalLocalRequests;
            snapshot.totalRequests = totalRequests;
            snapshot.averageRunningRequests = runningRequestsAverage.currentValue();
            snapshot.averageRunningLocalRequests = runningLocalRequestsAverage.currentValue();
            return snapshot;
        }
    }
    
    private final HashMap<Node, NodeStats> statsByNode = new HashMap<Node, NodeStats>();
    private int runningLocalInserts;
    private final TimeRunningAverage averageRunningLocalInserts = new TimeRunningAverage();
    
    private synchronized NodeStats makeStats(Node node) {
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
        stats.runningRequestsAverage.report(stats.runningRequests);
        if(tag.isLocal()) {
            stats.runningLocalRequests++;
            stats.totalLocalRequests++;
            stats.runningLocalRequestsAverage.report(stats.runningLocalRequests);
        }
        if(tag.isLocal() && tag.isInsert()) {
            runningLocalInserts++;
            averageRunningLocalInserts.report(runningLocalInserts);
        }
        super.onLock(tag, node);
    }

    @Override
    public synchronized void onUnlock(UIDTag tag, Node node) {
        NodeStats stats = makeStats(node);
        stats.runningRequests--;
        stats.runningRequestsAverage.report(stats.runningRequests);
        if(tag.isLocal()) {
            stats.runningLocalRequests--;
            stats.runningLocalRequestsAverage.report(stats.runningLocalRequests);
        }
        if(tag.isLocal() && tag.isInsert()) {
            runningLocalInserts--;
            averageRunningLocalInserts.report(runningLocalInserts);
        }
        super.onUnlock(tag, node);
    }
    
    public synchronized NodeStatsSnapshot getStats(Node node) {
        NodeStats stats = makeStats(node);
        return stats.snapshot();
    }
    
    public synchronized long getTotalRunningLocalInserts() {
        return runningLocalInserts;
    }
    
    public synchronized double getAverageTotalRunningLocalInserts() {
        return averageRunningLocalInserts.currentValue();
    }
    
    public synchronized void resetAverages() {
        long now = System.currentTimeMillis();
        averageRunningLocalInserts.reset();
        for(NodeStats stats : statsByNode.values()) {
            stats.runningLocalRequestsAverage.reset(now, stats.runningLocalRequests);
            stats.runningRequestsAverage.reset(now, stats.runningRequests);
            stats.totalLocalRequests = stats.runningLocalRequests;
            stats.totalRequests = stats.runningRequests;
        }
    }
    
}
