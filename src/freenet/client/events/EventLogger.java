/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.events;

import freenet.client.async.ClientContext;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

/**
 * Event handeling for clients.
 *
 * @author oskar
 */
public class EventLogger implements ClientEventListener {

	final LogLevel logPrio;
	final boolean removeWithProducer;
	
	public EventLogger(LogLevel prio, boolean removeWithProducer) {
		logPrio = prio;
		this.removeWithProducer = removeWithProducer;
	}
	
    /**
     * Logs an event
     * 
     * @param ce
     *            The event that occured
     */
	@Override
    public void receive(ClientEvent ce, ClientContext context) {
    	Logger.logStatic(ce, ce.getDescription(), logPrio);
    }
}
