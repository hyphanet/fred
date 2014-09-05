/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import freenet.node.RequestClient;
import freenet.support.io.ResumeFailedException;

/**
 * A client process. Something that initiates requests, and can cancel them. FCP, FProxy, and the
 * GlobalPersistentClient, implement this somewhere.
 */
public interface ClientBaseCallback {
	
	/**
	 * Called for a persistent request when the node is restarted. Must re-register with whatever
	 * infrastructure the request is using, e.g. PersistentRequestRoot, persistent temp buckets etc.
	 * @param context
	 */
	public void onResume(ClientContext context) throws ResumeFailedException;
	
	/** Get the RequestClient context object used to indicate which requests are related to each
	 * other for scheduling purposes. */
	public RequestClient getRequestClient();
}
