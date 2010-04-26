package freenet.node.fcp.whiteboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This class implements a generic whiteboard. A whiteboard is an object that event listeners can register themselves, and event producers can broadcast their events. With this,
 * the producers can come and go, they aren't needed to be present for the event system to work.
 * 
 * LOCKING WARNING: THIS CLASS IS NOT SYNCHRONIZED! 
 * If addListener() is only called during initialisation, and event is only called from one thread, this is okay.
 * But it's not very generic!
 */
public class Whiteboard {

	/** It stores the listeners for keys */
	private Map<String, List<WhiteboardListener>>	listeners	= new HashMap<String, List<WhiteboardListener>>();

	/**
	 * Adds a listener to a key. If a key gets notified, the listener will too.
	 * 
	 * @param listener
	 *            - The listener to be added
	 */
	public void addListener(String key, WhiteboardListener listener) {
		if (listeners.containsKey(key) == false) {
			listeners.put(key, new ArrayList<WhiteboardListener>());
		}
		listeners.get(key).add(listener);
	}

	/**
	 * Removes a listener completely, it will not receive any events for any keys.
	 * 
	 * @param listener
	 *            - The listener to be removed
	 */
	public void removeListener(WhiteboardListener listener) {
		for (String key : listeners.keySet()) {
			listeners.get(key).remove(listener);
		}
	}

	/**
	 * Called by an event producer, this indicates that an event occured for a key. All listeners will be notified and a msg object will be passed to them.
	 * 
	 * @param key
	 *            - The listeners registered to this key will be notified
	 * @param msg
	 *            - A message object that will be passed to the listeners
	 */
	public void event(String key, Object msg) {
		if (listeners.containsKey(key)) for (WhiteboardListener l : listeners.get(key)) {
			l.onEvent(key, msg);
		}
	}
}
