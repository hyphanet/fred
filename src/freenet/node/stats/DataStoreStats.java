/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.stats;

/**
 * This interface represents the data we can publish on our stats page for a given instance of a data store.
 *
 * @author nikotyan
 */
public interface DataStoreStats {

	final long FIX_32_KB = 32 * 1024;

	long keys();

	long capacity();

	long dataSize();

	public double utilization();

	public long readRequests();

	public long successfulReads();

	public double successRate() throws StatsNotAvailableException;

	public long writes();

	public double accessRate(long nodeUptimeSeconds);

	public double writeRate(long nodeUptimeSeconds);

	public long falsePos();

	double avgLocation() throws StatsNotAvailableException;

	double avgSuccess() throws StatsNotAvailableException;

	double furthestSuccess() throws StatsNotAvailableException;

	double avgDist() throws StatsNotAvailableException;

	double distanceStats() throws StatsNotAvailableException;
}
