package freenet.store.caching;

import java.io.IOException;

import freenet.node.stats.StoreAccessStats;
import freenet.node.useralerts.UserAlertManager;
import freenet.store.BlockMetadata;
import freenet.store.FreenetStore;
import freenet.store.KeyCollisionException;
import freenet.store.StorableBlock;
import freenet.support.Ticker;

public class SleepingFreenetStore<T extends StorableBlock> implements FreenetStore<T> {

	private final int delay;
	private final FreenetStore<T> underlying;
	
	public SleepingFreenetStore(int delay,
			FreenetStore<T> underlying) {
		this.delay = delay;
		this.underlying = underlying;
	}

	@Override
	public T fetch(byte[] routingKey, byte[] fullKey, boolean dontPromote,
			boolean canReadClientCache, boolean canReadSlashdotCache,
			boolean ignoreOldBlocks, BlockMetadata meta) throws IOException {
		return underlying.fetch(routingKey, fullKey, dontPromote, canReadClientCache, canReadSlashdotCache, ignoreOldBlocks, meta);
	}

	@Override
	public void put(T block, byte[] data, byte[] header, boolean overwrite,
			boolean oldBlock) throws IOException, KeyCollisionException {
		try {
			Thread.sleep(delay);
		} catch (InterruptedException e) {
			// Ignore.
		}
		underlying.put(block, data, header, overwrite, oldBlock);
	}

	// FIXME factor out ProxyFreenetStore for other methods
	
	@Override
	public void setMaxKeys(long maxStoreKeys, boolean shrinkNow)
			throws IOException {
		underlying.setMaxKeys(maxStoreKeys, shrinkNow);
	}

	@Override
	public long getMaxKeys() {
		return underlying.getMaxKeys();
	}

	@Override
	public long hits() {
		return underlying.hits();
	}

	@Override
	public long misses() {
		return underlying.misses();
	}

	@Override
	public long writes() {
		return underlying.writes();
	}

	@Override
	public long keyCount() {
		return underlying.keyCount();
	}

	@Override
	public long getBloomFalsePositive() {
		return underlying.getBloomFalsePositive();
	}

	@Override
	public boolean probablyInStore(byte[] routingKey) {
		return underlying.probablyInStore(routingKey);
	}

	@Override
	public StoreAccessStats getSessionAccessStats() {
		return underlying.getSessionAccessStats();
	}

	@Override
	public StoreAccessStats getTotalAccessStats() {
		return underlying.getTotalAccessStats();
	}

	@Override
	public boolean start(Ticker ticker, boolean longStart) throws IOException {
		return underlying.start(ticker, longStart);
	}

	@Override
	public void close() {
		underlying.close();
	}

	@Override
	public void setUserAlertManager(UserAlertManager userAlertManager) {
		underlying.setUserAlertManager(userAlertManager);
	}

	@Override
	public FreenetStore<T> getUnderlyingStore() {
		return underlying;
	}

}
