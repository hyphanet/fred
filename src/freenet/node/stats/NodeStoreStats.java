package freenet.node.stats;

/**
 * This interface represents aggregated stats for data store
 * User: nikotyan
 * Date: Apr 16, 2010
 */
public interface NodeStoreStats {
    double avgLocation() throws StatsNotAvailableException;

    double avgSuccess() throws StatsNotAvailableException;

    double furthestSuccess() throws StatsNotAvailableException;

    double avgDist() throws StatsNotAvailableException;

    double distanceStats() throws StatsNotAvailableException;
    
}
