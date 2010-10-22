/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.stats;

import freenet.store.StoreCallback;

/**
 * This class wraps StoreCallback instance to provide methods required to display stats
 *
 * @author nikotyan
 */
public class StoreCallbackStats implements DataStoreStats {

	private final StoreCallback<?> storeStats;
	private final StoreLocationStats nodeStats;
	public final StoreAccessStats sessionAccessStats;
	/** If the store type does not support this, it will be null, to avoid producing bogus
	 * numbers. */
	public final StoreAccessStats totalAccessStats;

	public StoreCallbackStats(StoreCallback<?> delegate, StoreLocationStats nodeStats) {
		this.storeStats = delegate;
		this.nodeStats = nodeStats;
		this.sessionAccessStats = delegate.getSessionAccessStats();
		this.totalAccessStats = delegate.getTotalAccessStats();
	}

	public long keys() {
		return storeStats.keyCount();
	}

	public long capacity() {
		return storeStats.getMaxKeys();
	}

	public long dataSize() {
		return keys() * storeStats.dataLength();
	}

	public double avgLocation() throws StatsNotAvailableException {
		return nodeStats.avgLocation();
	}

	public double utilization() {
		return (1.0 * keys() / capacity());
	}

	public double avgSuccess() throws StatsNotAvailableException {
		return nodeStats.avgSuccess();
	}

	public double furthestSuccess() throws StatsNotAvailableException {
		return nodeStats.furthestSuccess();
	}

	public double avgDist() throws StatsNotAvailableException {
		return nodeStats.avgDist();
	}

	public double distanceStats() throws StatsNotAvailableException {
		return nodeStats.distanceStats();
	}
	
	public StoreAccessStats getSessionAccessStats() {
		return sessionAccessStats;
	}
	
	public StoreAccessStats getTotalAccessStats() throws StatsNotAvailableException {
		if(totalAccessStats == null) throw new StatsNotAvailableException();
		return totalAccessStats;
	}
}
