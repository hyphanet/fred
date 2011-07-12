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

	@Override
	public long keys() {
		return storeStats.keyCount();
	}

	@Override
	public long capacity() {
		return storeStats.getMaxKeys();
	}

	@Override
	public long dataSize() {
		return keys() * storeStats.dataLength();
	}

	@Override
	public double avgLocation() throws StatsNotAvailableException {
		return nodeStats.avgLocation();
	}

	@Override
	public double utilization() {
		return (1.0 * keys() / capacity());
	}

	@Override
	public double avgSuccess() throws StatsNotAvailableException {
		return nodeStats.avgSuccess();
	}

	@Override
	public double furthestSuccess() throws StatsNotAvailableException {
		return nodeStats.furthestSuccess();
	}

	@Override
	public double avgDist() throws StatsNotAvailableException {
		return nodeStats.avgDist();
	}

	@Override
	public double distanceStats() throws StatsNotAvailableException {
		return nodeStats.distanceStats();
	}
	
	@Override
	public StoreAccessStats getSessionAccessStats() {
		return sessionAccessStats;
	}
	
	@Override
	public StoreAccessStats getTotalAccessStats() throws StatsNotAvailableException {
		if(totalAccessStats == null) throw new StatsNotAvailableException();
		return totalAccessStats;
	}
}
