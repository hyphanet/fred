/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.events;

/**
 * Event handling for clients.
 * 
 * @author oskar
 */
public interface ClientEvent {

	/**
	 * Returns a string describing the event.
	 */
	public String getDescription();

	/**
	 * Returns a unique code for this event.
	 */
	public int getCode();

}
