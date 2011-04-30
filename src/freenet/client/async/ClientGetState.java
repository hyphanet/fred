/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import com.db4o.ObjectContainer;

/**
 * A ClientGetState.
 * Represents a stage in the fetch process.
 */
public interface ClientGetState {

	/** Schedule the request on the ClientRequestScheduler. */
	public void schedule(ObjectContainer container, ClientContext context) throws KeyListenerConstructionException;

	/** Cancel the request, and call onFailure() on the callback in order to tell 
	 * downstream (ultimately the client) that cancel has succeeded, and to allow
	 * it to call removeFrom() to avoid a database leak. */
	public void cancel(ObjectContainer container, ClientContext context);

	/** Get a long value which may be passed around to identify this request (e.g. by the USK fetching code). */
	public long getToken();

	
	
	/**
	 * Once the callback has finished with this fetch, it will call removeFrom() to instruct the fetch
	 * to remove itself and all its subsidiary objects from the database.
	 * WARNING: It is possible that the caller will get deactivated! Be careful...
	 * @param container
	 */
	public void removeFrom(ObjectContainer container, ClientContext context);
}
