/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.stats;

import freenet.node.Location;

/**
 * This interface represents the data we can publish on our stats page for a given instance of a data store.
 *
 * @author nikotyan
 */
public interface DataStoreStats {
	long keys();

	long capacity();

	long dataSize();

	public double utilization();

	Location avgLocation() throws StatsNotAvailableException;

	Location avgSuccess() throws StatsNotAvailableException;

	double furthestSuccess() throws StatsNotAvailableException;

	double avgDist() throws StatsNotAvailableException;

	double distanceStats() throws StatsNotAvailableException;
	
	StoreAccessStats getSessionAccessStats();
	
	StoreAccessStats getTotalAccessStats() throws StatsNotAvailableException;

}
