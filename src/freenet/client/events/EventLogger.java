/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.events;

import freenet.support.Logger;

/**
 * Event handeling for clients.
 *
 * @author oskar
 */
public class EventLogger implements ClientEventListener {

	final int logPrio;
	
	public EventLogger(int prio) {
		logPrio = prio;
	}
	
    /**
     * Logs an event
     * 
     * @param ce
     *            The event that occured
     */
    public void receive(ClientEvent ce) {
    	Logger.logStatic(ce, ce.getDescription(), logPrio);
    }
}
