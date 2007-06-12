package freenet.client.async;

import java.util.HashMap;
import java.util.Set;

import freenet.keys.Key;
import freenet.keys.KeyBlock;

/** 
 * Simple BlockSet implementation, keeps all keys in RAM.
 * 
 * @author toad
 */
public class SimpleBlockSet implements BlockSet {

	private final HashMap blocksByKey = new HashMap();
	
	public synchronized void add(KeyBlock block) {
		blocksByKey.put(block.getKey(), block);
	}

	public KeyBlock get(Key key) {
		return (KeyBlock) blocksByKey.get(key);
	}

	public Set keys() {
		return blocksByKey.keySet();
	}

}
