package freenet.client.update;

/** This interface provides functionality to handle element updates */
public interface IUpdateManager {
	/**
	 * A notification received that an element needs to be updated
	 * 
	 * @param message
	 *            - The message about the update
	 */
	public void updated(String message);
}
