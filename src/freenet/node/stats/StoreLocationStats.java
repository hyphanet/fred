/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.stats;

/**
 * This interface represents aggregated stats for data store
 *
 * @author nikotyan
 */
public interface StoreLocationStats {

	double avgLocation() throws StatsNotAvailableException;

	double avgSuccess() throws StatsNotAvailableException;

	double furthestSuccess() throws StatsNotAvailableException;

	double avgDist() throws StatsNotAvailableException;

	double distanceStats() throws StatsNotAvailableException;

}
