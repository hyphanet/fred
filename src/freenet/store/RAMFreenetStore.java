package freenet.store;

import java.io.IOException;
import java.util.Arrays;

import com.sleepycat.je.DatabaseException;

import freenet.keys.KeyVerifyException;
import freenet.support.ByteArrayWrapper;
import freenet.support.LRUHashtable;

/**
 * LRU in memory store.
 * 
 * For debugging / simulation only
 */
public class RAMFreenetStore implements FreenetStore {

	private final static class Block {
		byte[] header;
		byte[] data;
		byte[] fullKey;
	}
	
	private final LRUHashtable<ByteArrayWrapper, Block> blocksByRoutingKey;
	
	private final StoreCallback callback;
	
	private int maxKeys;
	
	private long hits;
	private long misses;
	private long writes;
	
	public RAMFreenetStore(StoreCallback callback, int maxKeys) {
		this.callback = callback;
		this.blocksByRoutingKey = new LRUHashtable<ByteArrayWrapper, Block>();
		this.maxKeys = maxKeys;
		callback.setStore(this);
	}
	
	public synchronized StorableBlock fetch(byte[] routingKey, byte[] fullKey,
			boolean dontPromote) throws IOException {
		ByteArrayWrapper key = new ByteArrayWrapper(routingKey);
		Block block = blocksByRoutingKey.get(key);
		if(block == null) {
			misses++;
			return null;
		}
		try {
			StorableBlock ret =
				callback.construct(block.data, block.header, routingKey, block.fullKey);
			hits++;
			if(!dontPromote)
				blocksByRoutingKey.push(key, block);
			return ret;
		} catch (KeyVerifyException e) {
			blocksByRoutingKey.removeKey(key);
			misses++;
			return null;
		}
	}

	public synchronized long getMaxKeys() {
		return maxKeys;
	}

	public synchronized long hits() {
		return hits;
	}

	public synchronized long keyCount() {
		return blocksByRoutingKey.size();
	}

	public synchronized long misses() {
		return misses;
	}

	public synchronized void put(StorableBlock block, byte[] data, byte[] header, boolean overwrite) throws KeyCollisionException {
		byte[] routingkey = block.getRoutingKey();
		byte[] fullKey = block.getFullKey();
		
		writes++;
		ByteArrayWrapper key = new ByteArrayWrapper(routingkey);
		Block oldBlock = blocksByRoutingKey.get(key);
		boolean storeFullKeys = callback.storeFullKeys();
		if(oldBlock != null) {
			if(callback.collisionPossible()) {
				boolean equals = Arrays.equals(oldBlock.data, data) &&
					Arrays.equals(oldBlock.header, header) &&
					(storeFullKeys ? Arrays.equals(oldBlock.fullKey, fullKey) : true);
				if(equals) return;
				if(overwrite) {
					oldBlock.data = data;
					oldBlock.header = header;
					if(storeFullKeys)
						oldBlock.fullKey = fullKey;
				} else {
					throw new KeyCollisionException();
				}
				return;
			} else {
				return;
			}
		}
		Block storeBlock = new Block();
		storeBlock.data = data;
		storeBlock.header = header;
		if(storeFullKeys)
			storeBlock.fullKey = fullKey;
		blocksByRoutingKey.push(key, storeBlock);
		while(blocksByRoutingKey.size() > maxKeys) {
			blocksByRoutingKey.popKey();
		}
	}

	public synchronized void setMaxKeys(long maxStoreKeys, boolean shrinkNow)
			throws DatabaseException, IOException {
		this.maxKeys = (int)Math.min(Integer.MAX_VALUE, maxStoreKeys);
		// Always shrink now regardless of parameter as we will shrink on the next put() anyway.
		while(blocksByRoutingKey.size() > maxKeys) {
			blocksByRoutingKey.popKey();
		}
	}

	public long writes() {
		return writes;
	}

	public long getBloomFalsePositive() {
		return -1;
	}
	
	public boolean probablyInStore(byte[] routingKey) {
		ByteArrayWrapper key = new ByteArrayWrapper(routingKey);
		return blocksByRoutingKey.get(key) != null;
	}
}
