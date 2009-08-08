package freenet.client.updaters;

/** An Updater is used to update a given element type */
public interface IUpdater {
	/**
	 * Updates a given element with the given content
	 * 
	 * @param elementId
	 *            - The element that needs to be updated
	 * @param content
	 *            - The new content to update with
	 */
	public void updated(String elementId, String content);
}
