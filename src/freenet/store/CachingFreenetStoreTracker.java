package freenet.store;

import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import freenet.support.Logger;
import freenet.support.Ticker;

/**
 * CachingFreenetStoreTracker
 * 
 * @author Simon Vocella <voxsim@gmail.com>
 * 
*/
public class CachingFreenetStoreTracker {
    private static volatile boolean logMINOR;
    
    private final long maxSize;
	private final long period;
	private final ArrayList<CachingFreenetStore<?>> cachingStores;
	private final Ticker ticker;
	private final ReadWriteLock configLock = new ReentrantReadWriteLock();
	
	private boolean startJob;
	private long size;
	
    static { Logger.registerClass(CachingFreenetStore.class); }
    
	public CachingFreenetStoreTracker(long maxSize, long period, Ticker ticker) {
		if(ticker == null)
			throw new IllegalArgumentException();
		this.size = 0;
		this.maxSize = maxSize;
		this.period = period;
		this.startJob = false;
		this.cachingStores = new ArrayList<CachingFreenetStore<?>>();
		this.ticker = ticker;
	}

	/** register a CachingFreenetStore to be called when we get full or to flush all after a setted period. */
	public void registerCachingFS(CachingFreenetStore<?> fs) {
		synchronized (cachingStores) {
			cachingStores.add(fs);
		}
	}
	
	public void unregisterCachingFS(CachingFreenetStore<?> fs) {
		synchronized (cachingStores) {
			fs.pushAll();
			cachingStores.remove(fs);
		}
	}
	
	/** If we are close to the limit, we will schedule an off-thread job to flush ALL the caches. 
	 *  Even if we are not, we schedule one after period. If we are at the limit, we will return 
	 *  false, and the caller should write directly to the underlying store.  */
	public boolean add(long sizeBlock) {
		configLock.writeLock().lock();
		this.size += sizeBlock;
		
		//Check max size
		if(this.size > this.maxSize) {
			pushAllCachingStores();
			configLock.writeLock().unlock();
			return false;
		} else {
			//Check period
			if(!startJob) {
				configLock.writeLock().lock();
				startJob = true;
				this.ticker.queueTimedJob(new Runnable() {
					@Override
					public void run() {
						configLock.writeLock().lock();
						try {
							pushAllCachingStores();
						} finally {
							startJob = false;
							configLock.writeLock().unlock();
						}
					}
				}, period);
				configLock.writeLock().unlock();
			}
			configLock.writeLock().unlock();
			return true;
		}
	}
	
	private void pushAllCachingStores() {
		CopyOnWriteArrayList<CachingFreenetStore<?>> snapshot = new CopyOnWriteArrayList<CachingFreenetStore<?>>(this.cachingStores);
		
		for(CachingFreenetStore<?> cfs : snapshot) {
			cfs.pushAll();
		}
		
		configLock.writeLock().lock();
		size = 0;
		configLock.writeLock().unlock();
	}
}