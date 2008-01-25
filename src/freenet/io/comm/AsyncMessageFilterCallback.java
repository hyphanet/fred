/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.io.comm;

/**
 * Sometimes it really is simpler to do things asynchronously. And sometimes it's essential for performance.
 * @author toad
 */
public interface AsyncMessageFilterCallback {

	/**
	 * Called when the filter is matched. It will have been removed before the callback is called,
	 * and no locks should be held.
	 * @param m The message which matched the filter.
	 */
	void onMatched(Message m);
	
	/**
	 * Check whether the filter should be removed. Note that USM locks may be held by the caller
	 * when this is called: the implementation should not do anything that might cause USM-related 
	 * locks to be taken or messages to be sent.
	 * @return True if the filter should be immediately timed out.
	 */
	boolean shouldTimeout();
	
	/**
	 * Called when the filter times out and is removed from the list of filters to match.
	 */
	void onTimeout();

	/**
	 * Called when the filter is dropped because a connection is dropped.
	 * @param ctx
	 */
	void onDisconnect(PeerContext ctx);

	/**
	 * Called when the filter is dropped because a connection is restarted.
	 * @param ctx
	 */
	void onRestarted(PeerContext ctx);
}
