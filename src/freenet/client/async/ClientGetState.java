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

	public void schedule(ObjectContainer container, ClientContext context) throws KeyListenerConstructionException;

	public void cancel(ObjectContainer container, ClientContext context);

	public long getToken();
}
