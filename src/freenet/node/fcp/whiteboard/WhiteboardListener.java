package freenet.node.fcp.whiteboard;

/** A WhiteboardListener can be registered to the Whiteboard, and will be notified when an event occurs. */
public interface WhiteboardListener {

	/**
	 * Invoked when an event occurs with the id of the event provider and a msg object passed alon.
	 * 
	 * @param id
	 *            - The identifier of the event producer
	 * @param msg
	 *            - A message object that can contain information about the event or the provider
	 */
	public void onEvent(String id, Object msg);
}
