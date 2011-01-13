package freenet.store;

import java.io.IOException;

import com.sleepycat.je.DatabaseException;

import freenet.node.stats.StoreAccessStats;

public class NullFreenetStore<T extends StorableBlock> implements FreenetStore<T> {

	public NullFreenetStore(StoreCallback<T> callback) {
		callback.setStore(this);
	}

	public T fetch(byte[] routingKey, byte[] fullKey,
			boolean dontPromote, boolean canReadClientCache,
			boolean canReadSlashdotCache, boolean ignoreOldBlocks, BlockMetadata meta) throws IOException {
		// No block returned so don't set meta.
		return null;
	}

	public long getBloomFalsePositive() {
		return 0;
	}

	public long getMaxKeys() {
		return 0;
	}

	public long hits() {
		return 0;
	}

	public long keyCount() {
		return 0;
	}

	public long misses() {
		return 0;
	}

	public boolean probablyInStore(byte[] routingKey) {
		return false;
	}

	public void put(T block, byte[] data, byte[] header,
			boolean overwrite, boolean oldBlock) throws IOException,
			KeyCollisionException {
		// Do nothing
	}

	public void setMaxKeys(long maxStoreKeys, boolean shrinkNow)
			throws DatabaseException, IOException {
		// Do nothing
	}

	public long writes() {
		return 0;
	}

	public StoreAccessStats getSessionAccessStats() {
		return new StoreAccessStats() {

			@Override
			public long hits() {
				return 0;
			}

			@Override
			public long misses() {
				return 0;
			}

			@Override
			public long falsePos() {
				return 0;
			}

			@Override
			public long writes() {
				return 0;
			}
			
		};
	}

	public StoreAccessStats getTotalAccessStats() {
		return null;
	}

}
