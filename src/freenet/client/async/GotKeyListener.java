package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.node.SendableGet;

public interface GotKeyListener {

	/**
	 * Callback for when a block is found. Will be called on the database executor thread.
	 * @param key
	 * @param block
	 * @param sched
	 */
	public abstract void onGotKey(Key key, KeyBlock block, ObjectContainer container, ClientContext context);
	
	/**
	 * What keys are we interested in?
	 * @param container Database handle.
	 */
	Key[] listKeys(ObjectContainer container);

	/**
	 * Is this related to a persistent request?
	 */
	boolean persistent();

	/**
	 * Priority of the associated request.
	 * @param container Database handle.
	 */
	short getPriorityClass(ObjectContainer container);

	/**
	 * Is the request cancelled/finished/invalid?
	 * @param container Database handle.
	 */
	boolean isCancelled(ObjectContainer container);

	/**
	 * Get the SendableGet for a specific key, if any.
	 * Used in requeueing requests after a cooldown has expired.
	 * @param key The key.
	 * @param container The database handle.
	 * @return Null if we don't want to register a request for the key,
	 * otherwise the SendableGet.
	 */
	public abstract SendableGet getRequest(Key key, ObjectContainer container);

	/**
	 * @return True if when checking the datastore on initial registration, we
	 * should not promote any blocks found.
	 */
	public abstract boolean dontCache(ObjectContainer container);

}
