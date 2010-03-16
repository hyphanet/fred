/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.events;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;


/**
 * Event handling for clients.
 *
 * @author oskar
 **/


public interface ClientEventListener {

	/**
	 * Hears an event.
	 * @param container The database context the event was generated in.
	 * NOTE THAT IT MAY NOT HAVE BEEN GENERATED IN A DATABASE CONTEXT AT ALL:
	 * In this case, container will be null, and you should use context to schedule a DBJob.
	 **/
	public void receive(ClientEvent ce, ObjectContainer maybeContainer, ClientContext context);

	/**
	 * Called when the EventProducer gets removeFrom(ObjectContainer).
	 * If the listener is the main listener which probably called removeFrom(), it should do nothing.
	 * If it's a tag-along but request specific listener, it may need to remove itself.
	 */
	public void onRemoveEventProducer(ObjectContainer container);

}
