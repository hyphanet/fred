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

	private final HashMap<Key, KeyBlock> blocksByKey = new HashMap<Key, KeyBlock>();
	
	public synchronized void add(KeyBlock block) {
		blocksByKey.put(block.getKey(), block);
	}

	public synchronized KeyBlock get(Key key) {
		return blocksByKey.get(key);
	}

	public synchronized Set<Key> keys() {
		return blocksByKey.keySet();
	}

	public ClientKeyBlock get(ClientKey key) {
		KeyBlock block = get(key.getNodeKey(false));
		if(block == null) return null;
		try {
			return Key.createKeyBlock(key, block);
		} catch (KeyVerifyException e) {
			Logger.error(this, "Caught decoding block with "+key+" : "+e, e);
			return null;
		}
	}
	
	public boolean objectCanNew(ObjectContainer container) {
		/* Storing a BlockSet is not supported. There are some complications, so lets
		 * not implement this until FCP supports it (currently we can't do fetch-from-blob,
		 * we can only do fetch-to-blob and insert-blob).
		 * 
		 * The major problems are:
		 * - In both CHKBlock and SSKBlock, who is responsible for deleting the node keys? We
		 *   have to have them in the objects.
		 * - In SSKBlock, who is responsible for deleting the DSAPublicKey? And the DSAGroup?
		 *   A group might be unique or might be shared between very many SSKs...
		 * 
		 * Especially in the second case, we don't want to just copy every time even for
		 * transient uses ... the best solution may be to copy in objectCanNew(), but even
		 * then callers to the relevant getter methods may be a worry.
		 */
		throw new UnsupportedOperationException("Block set storage in database not supported");
	}

}
