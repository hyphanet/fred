/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

/**
 * Interface which returns the time at which the failure table timeout on any given node will 
 * expire for a specific key.
 * @author toad
 */
public interface TimedOutNodesList {

	/** When does the timeout for this node end?
	 * @param peer The peer we are proposing to route to.
	 * @param htl Timeouts with lower HTL than this will be ignored.
	 * @param now The current time from System.currentTimeMillis().
	 * @param forPerNodeFailureTables If true, return the timeout for purposes of
	 * per-node failure tables i.e. which to route to (paranoid high); if false,
	 * return the timeout for purposes of RecentlyFailed request quenching 
	 * (trusting low).
	 * @return The time at which the timeout ends for the node in question.
	 * -1 if there is no timeout. */
	long getTimeoutTime(PeerNode peer, short htl, long now, boolean forPerNodeFailureTables);

}
