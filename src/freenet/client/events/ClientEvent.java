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
