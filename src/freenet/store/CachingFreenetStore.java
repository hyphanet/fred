package freenet.store;

import java.io.IOException;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import freenet.keys.KeyVerifyException;
import freenet.node.SemiOrderedShutdownHook;
import freenet.node.stats.StoreAccessStats;
import freenet.node.useralerts.UserAlertManager;
import freenet.support.ByteArrayWrapper;
import freenet.support.LRUMap;
import freenet.support.Logger;
import freenet.support.Ticker;
import freenet.support.io.NativeThread;

/**
 * CachingFreenetStore
 * 
 * @author Simon Vocella <voxsim@gmail.com>
 * 
 */
public class CachingFreenetStore<T extends StorableBlock> implements FreenetStore<T> {
    private static volatile boolean logMINOR;
 
	private boolean shuttingDown; /* If this flag is true, we don't accept puts anymore */
	private final LRUMap<ByteArrayWrapper, Block<T>> blocksByRoutingKey;
	private final StoreCallback<T> callback;
	private final FreenetStore<T> backDatastore;
	private final boolean collisionPossible;
	private final ReadWriteLock configLock = new ReentrantReadWriteLock();
	private final CachingFreenetStoreTracker tracker;
	private final int sizeBlock;
	
    static { Logger.registerClass(CachingFreenetStore.class); }
    
	private final static class Block<T> {
		T block;
		byte[] data;
		byte[] header;
		boolean overwrite;
		boolean isOldBlock;
	}

	public CachingFreenetStore(StoreCallback<T> callback, FreenetStore<T> backDatastore, CachingFreenetStoreTracker tracker) {
		this.callback = callback;
		this.backDatastore = backDatastore;
		SemiOrderedShutdownHook shutdownHook = SemiOrderedShutdownHook.get();
		this.blocksByRoutingKey = LRUMap.createSafeMap(ByteArrayWrapper.FAST_COMPARATOR);
		this.collisionPossible = callback.collisionPossible();
		this.shuttingDown = false;
		this.tracker = tracker;
		this.sizeBlock = callback.dataLength() + callback.headerLength() + callback.fullKeyLength() + callback.routingKeyLength();
		
		callback.setStore(this);
		shutdownHook.addEarlyJob(new NativeThread("Close CachingFreenetStore", NativeThread.HIGH_PRIORITY, true) {
			@Override
			public void realRun() {
				innerClose(); // SaltedHashFS has its own shutdown job.
			}
		});
	}

	@Override
	public T fetch(byte[] routingKey, byte[] fullKey,
			boolean dontPromote, boolean canReadClientCache,
			boolean canReadSlashdotCache, boolean ignoreOldBlocks, BlockMetadata meta) 
			throws IOException {
		ByteArrayWrapper key = new ByteArrayWrapper(routingKey);
		
		Block<T> block = null;
		
		configLock.readLock().lock();
		try {
			block = blocksByRoutingKey.get(key);
		} finally {
			configLock.readLock().unlock();
		}
		
		if(block != null) {
			try {
				return this.callback.construct(block.data, block.header, routingKey, block.block.getFullKey(), canReadClientCache, canReadSlashdotCache, meta, null);
			} catch (KeyVerifyException e) {
				Logger.error(this, "Error in fetching for CachingFreenetStore: "+e, e);
			}
		}
		
		return backDatastore.fetch(routingKey, fullKey, dontPromote, canReadClientCache, canReadSlashdotCache, ignoreOldBlocks, meta);	
	}

	@Override
	public long getBloomFalsePositive() {
		return backDatastore.getBloomFalsePositive();
	}

	@Override
	public long getMaxKeys() {
		return backDatastore.getMaxKeys();
	}

	@Override
	public long hits() {
		return backDatastore.hits();
	}

	@Override
	public long keyCount() {
		return backDatastore.keyCount();
	}

	@Override
	public long misses() {
		return backDatastore.misses();
	}

	@Override
	public boolean probablyInStore(byte[] routingKey) {
		ByteArrayWrapper key = new ByteArrayWrapper(routingKey);
		Block<T> block = null;
		
		configLock.readLock().lock();
		try {
			block = blocksByRoutingKey.get(key);
		} finally {
			configLock.readLock().unlock();
		}
		
		return block != null || backDatastore.probablyInStore(routingKey);
	}
	
	@Override
	public void put(T block, byte[] data, byte[] header,
			boolean overwrite, boolean isOldBlock) throws IOException, KeyCollisionException {
		byte[] routingKey = block.getRoutingKey();
		final ByteArrayWrapper key = new ByteArrayWrapper(routingKey);
		
		Block<T> storeBlock = new Block<T>();
		storeBlock.block = block;
		storeBlock.data = data;
		storeBlock.header = header;
		storeBlock.overwrite = overwrite;
		storeBlock.isOldBlock = isOldBlock;
		
		boolean cacheIt = true;
		
		//Case cache it
		configLock.writeLock().lock();
		
		try {
			if(!shuttingDown) {
				Block<T> previousBlock = blocksByRoutingKey.get(key);
			
				if(!collisionPossible || overwrite) {
					if(previousBlock == null) {
						cacheIt = tracker.add(sizeBlock);
					}
					
					if(cacheIt) {
						blocksByRoutingKey.push(key, storeBlock);
					}
				} else {
					//Case cache it but is it in blocksByRoutingKey? If so, throw a KCE
					if(previousBlock != null) {
						if(block.equals(previousBlock.block))
							return;
						throw new KeyCollisionException();
					}
					
					//Is probablyInStore()? If so, remove it from blocksByRoutingKey, and set a flag so we don't call put()
					if(backDatastore.probablyInStore(routingKey)) {
						cacheIt = false;
					} else {
						cacheIt = tracker.add(sizeBlock);
						
						if(cacheIt) {
							blocksByRoutingKey.push(key, storeBlock);
						}
					}
				}
			} else {
				cacheIt = false;
			}
		} finally {
			configLock.writeLock().unlock();
		}
		
		//Case don't cache it
		if(!cacheIt) {
			backDatastore.put(block, data, header, overwrite, isOldBlock);
			return;
		}
	}
	
	long pushLeastRecentlyBlock() {
		long sizeBlock = 0;
		Block<T> block = null;
		ByteArrayWrapper key = null;
		
		configLock.writeLock().lock();
		try {
			block = blocksByRoutingKey.peekValue();
			key = blocksByRoutingKey.peekKey();
		} finally {
			configLock.writeLock().unlock();
		}
			
		if(block != null) {
			try {
				backDatastore.put(block.block, block.data, block.header, block.overwrite, block.isOldBlock);
			} catch (IOException e) {
				Logger.error(this, "Error in pushAll for CachingFreenetStore: "+e, e);
			} catch (KeyCollisionException e) {
				if(logMINOR) Logger.minor(this, "KeyCollisionException in pushAll for CachingFreenetStore: "+e, e);
			}
			
			configLock.writeLock().lock();
			try {
				Block<T> lastVersionOfBlock = blocksByRoutingKey.get(key);
				
				/** it might have changed if there was a put() with overwrite=true. 
				 *  If it has changed, return 0 , i.e. don't remove it*/
				if(lastVersionOfBlock.equals(block)) {
					boolean removed = blocksByRoutingKey.removeKey(key);
					if(removed) sizeBlock = this.sizeBlock;
				}
			} finally {
				configLock.writeLock().unlock();
			}
		}
		return sizeBlock;
	}

	@Override
	public void setMaxKeys(long maxStoreKeys, boolean shrinkNow)
			throws IOException {
		backDatastore.setMaxKeys(maxStoreKeys, shrinkNow);
	}

	@Override
	public long writes() {
		return backDatastore.writes();
	}

	@Override
	public StoreAccessStats getSessionAccessStats() {
		return backDatastore.getSessionAccessStats();
	}

	@Override
	public StoreAccessStats getTotalAccessStats() {
		return backDatastore.getTotalAccessStats();
	}

	@Override
	public boolean start(Ticker ticker, boolean longStart) throws IOException {
		tracker.registerCachingFS(this);
		return this.backDatastore.start(ticker, longStart);
	}

	@Override
	public void setUserAlertManager(UserAlertManager userAlertManager) {
		this.backDatastore.setUserAlertManager(userAlertManager);
	}
	
	@Override
	public FreenetStore<T> getUnderlyingStore() {
		return this.backDatastore;
	}

	@Override
	public void close() {
		innerClose();
		backDatastore.close();
	}

	/** Close this store but not the underlying store. */
	private void innerClose() {
		configLock.writeLock().lock();
		try {
			shuttingDown = true;
			tracker.unregisterCachingFS(this);
		} finally {
			configLock.writeLock().unlock();
		}
	}
	
	public boolean isEmpty() {
		boolean isEmpty;
		configLock.readLock().lock();
		try {
			isEmpty = this.blocksByRoutingKey.isEmpty();
		} finally {
			configLock.readLock().unlock();
		}
		return isEmpty;
	}
}
