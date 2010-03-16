/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.events;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.support.Logger;

/**
 * Event handeling for clients.
 *
 * @author oskar
 */
public class EventLogger implements ClientEventListener {

	final int logPrio;
	final boolean removeWithProducer;
	
	public EventLogger(int prio, boolean removeWithProducer) {
		logPrio = prio;
		this.removeWithProducer = removeWithProducer;
	}

	/**
	 * Logs an event
	 *
	 * @param ce
	 *            The event that occured
	 */
	public void receive(ClientEvent ce, ObjectContainer container, ClientContext context) {
		Logger.logStatic(ce, ce.getDescription(), logPrio);
	}

	public void onRemoveEventProducer(ObjectContainer container) {
		if(removeWithProducer)
			container.delete(this);
	}
}
