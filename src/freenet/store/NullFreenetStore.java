package freenet.store;

import java.io.IOException;

import freenet.node.stats.StoreAccessStats;
import freenet.node.useralerts.UserAlertManager;
import freenet.support.Ticker;

public class NullFreenetStore<T extends StorableBlock> implements FreenetStore<T> {

	public NullFreenetStore(StoreCallback<T> callback) {
		callback.setStore(this);
	}

	@Override
	public T fetch(byte[] routingKey, byte[] fullKey,
			boolean dontPromote, boolean canReadClientCache,
			boolean canReadSlashdotCache, boolean ignoreOldBlocks, BlockMetadata meta) throws IOException {
		// No block returned so don't set meta.
		return null;
	}

	@Override
	public long getBloomFalsePositive() {
		return 0;
	}

	@Override
	public long getMaxKeys() {
		return 0;
	}

	@Override
	public long hits() {
		return 0;
	}

	@Override
	public long keyCount() {
		return 0;
	}

	@Override
	public long misses() {
		return 0;
	}

	@Override
	public boolean probablyInStore(byte[] routingKey) {
		return false;
	}

	@Override
	public void put(T block, byte[] data, byte[] header,
			boolean overwrite, boolean oldBlock) throws IOException,
			KeyCollisionException {
		// Do nothing
	}

	@Override
	public void setMaxKeys(long maxStoreKeys, boolean shrinkNow)
			throws IOException {
		// Do nothing
	}

	@Override
	public long writes() {
		return 0;
	}

	@Override
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

	@Override
	public StoreAccessStats getTotalAccessStats() {
		return null;
	}

	@Override
	public boolean start(Ticker ticker, boolean longStart) throws IOException {
		return false;
	}

	@Override
	public void setUserAlertManager(UserAlertManager userAlertManager) {
		// Do nothing
	}
	
	@Override
	public FreenetStore<T> getUnderlyingStore() {
		return this;
	}
}
