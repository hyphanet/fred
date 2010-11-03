/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.Set;

import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.Key;
import freenet.keys.KeyBlock;

/**
 * A set of KeyBlock's.
 * @author toad
 */
public interface BlockSet {

	/**
	 * Get a block by its key.
	 * @param key The key of the block to get.
	 * @return A block, or null if there is no block with that key.
	 */
	public KeyBlock get(Key key);
	
	/**
	 * Add a block.
	 * @param block The block to add.
	 */
	public void add(KeyBlock block);
	
	/**
	 * Get the set of all the keys of all the blocks.
	 * @return A set of the keys of the blocks in the BlockSet. Not guaranteed to be
	 * kept up to date. Read only.
	 */
	public Set<Key> keys();

	/** Get a high level block, given a high level key */
	public ClientKeyBlock get(ClientKey key);

}
