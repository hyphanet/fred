/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.stats;

/**
 * This implementation is used for data stores that have no aggregated stats
 *
 * @author nikotyan
 */
public class NotAvailNodeStoreStats implements StoreLocationStats {
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
