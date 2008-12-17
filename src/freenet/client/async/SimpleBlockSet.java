package freenet.client.async;

import java.util.HashMap;
import java.util.Set;

import com.db4o.ObjectContainer;

import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.KeyVerifyException;
import freenet.support.Logger;

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

	public synchronized KeyBlock get(Key key) {
		return (KeyBlock) blocksByKey.get(key);
	}

	public synchronized Set keys() {
		return blocksByKey.keySet();
	}

	public ClientKeyBlock get(ClientKey key) {
		KeyBlock block = get(key.getNodeKey());
		if(block == null) return null;
		try {
			return Key.createKeyBlock(key, block);
		} catch (KeyVerifyException e) {
			Logger.error(this, "Caught decoding block with "+key+" : "+e, e);
			return null;
		}
	}

	public void removeFrom(ObjectContainer container) {
		Key[] keys;
		KeyBlock[] blocks;
		synchronized(this) {
			keys = (Key[]) blocksByKey.keySet().toArray(new Key[blocksByKey.size()]);
			blocks = (KeyBlock[]) blocksByKey.values().toArray(new KeyBlock[blocksByKey.size()]);
		}
		for(Key key : keys)
			key.removeFrom(container);
		for(KeyBlock block : blocks)
			block.removeFrom(container);
	}

}
