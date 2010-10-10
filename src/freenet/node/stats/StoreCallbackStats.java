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
	private final NodeStoreStats nodeStats;

	public StoreCallbackStats(StoreCallback<?> delegate, NodeStoreStats nodeStats) {
		this.storeStats = delegate;
		this.nodeStats = nodeStats;
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

	private long Hits() {
		return storeStats.hits();
	}

	private long Misses() {
		return storeStats.misses();
	}

	public long readRequests() {
		return Hits() + Misses();
	}

	public long successfulReads() {
		if (readRequests() > 0)
			return Hits();
		else
			return 0;
	}

	public double successRate() throws StatsNotAvailableException {
		if (readRequests() > 0)
			return (100.0 * Hits() / readRequests());
		else
			throw new StatsNotAvailableException();
	}

	public long writes() {
		return storeStats.writes();
	}


	public double accessRate(long nodeUptimeSeconds) {
		return storeStats.accessRate();
	}

	public double writeRate(long nodeUptimeSeconds) {
		return storeStats.writeRate();
	}

	public long falsePos() {
		return storeStats.getBloomFalsePositive();
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
}
