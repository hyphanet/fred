package freenet.store;

import java.io.IOException;
import java.util.TreeMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import freenet.keys.KeyVerifyException;
import freenet.node.SemiOrderedShutdownHook;
import freenet.node.stats.StoreAccessStats;
import freenet.node.useralerts.UserAlertManager;
import freenet.support.ByteArrayWrapper;
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
    
	private long size;
	private boolean startJob;
	private boolean shuttingDown; /* If this flag is true, we don't accept puts anymore */
	
	private final long maxSize;
	private final long period;
	private final TreeMap<ByteArrayWrapper, Block<T>> blocksByRoutingKey;
	private final StoreCallback<T> callback;
	private final FreenetStore<T> backDatastore;
	private final Ticker ticker;
	private final boolean collisionPossible;
	private final ReadWriteLock configLock = new ReentrantReadWriteLock();
	
    static { Logger.registerClass(CachingFreenetStore.class); }
    
	private final static class Block<T> {
		T block;
		byte[] data;
		byte[] header;
		boolean overwrite;
		boolean isOldBlock;
	}

	public CachingFreenetStore(StoreCallback<T> callback, long maxSize, long period, FreenetStore<T> backDatastore, Ticker ticker) {
		if(ticker == null)
			throw new IllegalArgumentException();
		this.callback = callback;
		this.maxSize = maxSize;
		this.period = period;
		this.backDatastore = backDatastore;
		SemiOrderedShutdownHook shutdownHook = SemiOrderedShutdownHook.get();
		this.blocksByRoutingKey = new TreeMap<ByteArrayWrapper, Block<T>>(ByteArrayWrapper.FAST_COMPARATOR);
		this.ticker = ticker;
		this.size = 0;
		this.startJob = false;
		this.collisionPossible = callback.collisionPossible();
		this.shuttingDown = false;
		
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
			boolean overwrite, boolean isOldBlock) throws IOException,
			KeyCollisionException {
		byte[] routingKey = block.getRoutingKey();
		final ByteArrayWrapper key = new ByteArrayWrapper(routingKey);
		
		Block<T> storeBlock = new Block<T>();
		storeBlock.block = block;
		storeBlock.data = data;
		storeBlock.header = header;
		storeBlock.overwrite = overwrite;
		storeBlock.isOldBlock = isOldBlock;
		
		long sizeBlock = data.length+header.length+block.getFullKey().length+routingKey.length;	
		boolean cacheIt = true;
		
		//Case cache it
		configLock.writeLock().lock();
		
		try {
			if(sizeBlock < maxSize && !shuttingDown) {
				Block<T> previousBlock = blocksByRoutingKey.get(key);
			
				if(!collisionPossible || overwrite) {
					blocksByRoutingKey.put(key, storeBlock);
					
					if(previousBlock == null) {
						size += sizeBlock;
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
						blocksByRoutingKey.put(key, storeBlock);
						size += sizeBlock;
					}
				}
				
				//Check max size
				if(size > maxSize) {
					pushAll();
				} else {
					//Check period
					if(!blocksByRoutingKey.isEmpty() && !startJob) {
						startJob = true;
						this.ticker.queueTimedJob(new Runnable() {
							@Override
							public void run() {
								configLock.writeLock().lock();
								try {
									pushAll();
								} finally {
									startJob = false;
									configLock.writeLock().unlock();
								}
							}
						}, period);
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
	
	private void pushAll() {
		configLock.writeLock().lock();
		try
		{
			for(Block<T> block : blocksByRoutingKey.values()) {
					try {
						backDatastore.put(block.block, block.data, block.header, block.overwrite, block.isOldBlock);
					} catch (IOException e) {
						Logger.error(this, "Error in pushAll for CachingFreenetStore: "+e, e);
					} catch (KeyCollisionException e) {
						if(logMINOR) Logger.minor(this, "KeyCollisionException in pushAll for CachingFreenetStore: "+e, e);
					}
				}
			blocksByRoutingKey.clear();
			size = 0;
		} finally {
			configLock.writeLock().unlock();
		}
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
			pushAll();
		} finally {
			configLock.writeLock().unlock();
		}
	}
}
