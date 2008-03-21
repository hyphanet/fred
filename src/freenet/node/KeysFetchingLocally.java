package freenet.node;

import freenet.keys.Key;

public interface KeysFetchingLocally {
	
	/**
	 * Is this key currently being fetched locally?
	 */
	public boolean hasKey(Key key);

}
