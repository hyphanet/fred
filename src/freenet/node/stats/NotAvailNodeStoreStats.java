package freenet.node.stats;

/**
 * This implementation is used for data stores that have no aggregated stats
 * User: nikotyan
 * Date: Apr 16, 2010
 */
public class NotAvailNodeStoreStats implements NodeStoreStats {
    public double avgLocation() throws StatsNotAvailableException {
        throw new StatsNotAvailableException();
    }

    public double avgSuccess() throws StatsNotAvailableException {
        throw new StatsNotAvailableException();
    }

    public double furthestSuccess() throws StatsNotAvailableException {
        throw new StatsNotAvailableException();
    }

    public double avgDist() throws StatsNotAvailableException {
        throw new StatsNotAvailableException();
    }

    public double distanceStats() throws StatsNotAvailableException {
        throw new StatsNotAvailableException();
    }
}
