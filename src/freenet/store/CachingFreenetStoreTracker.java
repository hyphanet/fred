package freenet.store;

import java.util.ArrayList;

import freenet.support.Logger;
import freenet.support.Ticker;

/**
 * Tracks the memory used by a bunch of CachingFreenetStore's, and writes blocks to disk when full or 
 * after 5 minutes. One major objective here is we should not do disk I/O inside a lock, all methods 
 * should be non-blocking, even if it means the caller needs to do a blocking disk write.
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
		long sizeBlock = 0;
		do {
			sizeBlock = fs.pushLeastRecentlyBlock();
			synchronized(this) {
				size -= sizeBlock;
			}
		} while(fs.getSize() > 0);
		
		synchronized (cachingStores) {			
			cachingStores.remove(fs);
		}
	}
	
	/** If we are close to the limit, we will schedule an off-thread job to flush ALL the caches. 
	 *  Even if we are not, we schedule one after period. If we are at the limit, we will return 
	 *  false, and the caller should write directly to the underlying store.  */
	public synchronized boolean add(long sizeBlock) {
		
		//Check max size
		if(this.size + sizeBlock > this.maxSize) {
			//Here don't check the startJob because certainly an offline thread is already created with a timeQueue setted to period  
			startJob = true;
			
			this.ticker.queueTimedJob(new Runnable() {
				@Override
				public void run() {
					try {
						pushAllCachingStores();
					} finally {
						synchronized(this) {
							startJob = false;
						}
					}
				}
			}, 0);
			return false;
		} else {
			this.size += sizeBlock;
			
			//Check period
			if(!startJob) {
				startJob = true;
				this.ticker.queueTimedJob(new Runnable() {
					@Override
					public void run() {
						try {
							pushAllCachingStores();
						} finally {
							synchronized(this) {
								startJob = false;
							}
						}
					}
				}, period);
			}
			return true;
		}
	}
	
	private void pushAllCachingStores() {
		CachingFreenetStore<?>[] cachingStoresSnapshot = null;
		synchronized (cachingStores) {
			cachingStoresSnapshot = this.cachingStores.toArray(new CachingFreenetStore[cachingStores.size()]);
		}
		
		while(true) {
			for(CachingFreenetStore<?> cfs : cachingStoresSnapshot) {
				if(cfs.getSize() > 0) {
					long sizeBlock = cfs.pushLeastRecentlyBlock();
					synchronized(this) {
						size -= sizeBlock;
						if(size < 0) {
							Logger.error(this, "Cache broken: Size = "+size);
							size = 0;
						}
						if(size == 0) return;
					}
				}
			}
		}
	}
	
	public long getSizeOfCache() {
		long sizeReturned;
		synchronized(this) {
			sizeReturned = size;
		}
		return sizeReturned;
	}
}