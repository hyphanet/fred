/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import com.db4o.ObjectContainer;

/**
 * A client process. Something that initiates requests, and can cancel them. FCP, FProxy, and the
 * GlobalPersistentClient, implement this somewhere.
 */
public interface ClientBaseCallback {
	/**
	 * Called when freenet.async thinks that the request should be serialized to disk, if it is a
	 * persistent request.
	 */
	public void onMajorProgress(ObjectContainer container);
	
	/**
	 * Called for a persistent request when the node is restarted. Must re-register with whatever
	 * infrastructure the request is using, e.g. FCPPersistentRoot, persistent temp buckets etc.
	 * @param context
	 */
	public void onResume(ClientContext context);
}
