package freenet.node;

import freenet.keys.Key;

public interface KeysFetchingLocally {
	
	/**
	 * Is this key currently being fetched locally?
	 * LOCKING: This should be safe just about anywhere, the lock protecting it is always taken last.
	 */
	public boolean hasKey(Key key);

}
