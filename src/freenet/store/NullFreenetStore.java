package freenet.store;

import java.io.IOException;

import com.sleepycat.je.DatabaseException;

public class NullFreenetStore implements FreenetStore {

	public NullFreenetStore(StoreCallback callback) {
		callback.setStore(this);
	}

	public StorableBlock fetch(byte[] routingKey, byte[] fullKey,
			boolean dontPromote, boolean canReadClientCache,
			boolean canReadSlashdotCache) throws IOException {
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

	public void put(StorableBlock block, byte[] data, byte[] header,
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

}
