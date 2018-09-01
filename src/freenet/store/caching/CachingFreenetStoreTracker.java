package freenet.store.caching;

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
    
    /** Number of keys that it's pushed to the *underlying* store in the add function.
     * FIXME make this configurable??? */
    private static int numberOfKeysToWrite = 20;
    
    /** Lower threshold, when it will start a write job, but still accept the data. */
    private static double lowerThreshold = 0.9;
    
    private final long maxSize;
	private final long period;
	private final ArrayList<CachingFreenetStore<?>> cachingStores;
	private final Ticker ticker;
	
	/** Is a write job queued for some point in the next period? There should only be one such job 
	 * queued. However if we then run out of memory we will run a job immediately. */
	private boolean queuedJob;
	/** Is a write job running right now? This prevents us from running multiple pushAllCachingStores() 
	 * in parallel and thus wasting memory, even if we run out of memory and so have to run a job
	 * straight away. */
	private boolean runningJob;
	private long size;
	
    static { Logger.registerClass(CachingFreenetStore.class); }
    
	public CachingFreenetStoreTracker(long maxSize, long period, Ticker ticker) {
		if(ticker == null)
			throw new IllegalArgumentException();
		this.size = 0;
		this.maxSize = maxSize;
		this.period = period;
		this.queuedJob = false;
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
		while(true) {
			sizeBlock = fs.pushLeastRecentlyBlock();
			synchronized(this) {
				if(sizeBlock == -1)
					break;
				else
					size -= sizeBlock;
			}
		}
		
		synchronized (cachingStores) {			
			cachingStores.remove(fs);
		}
	}
	
	/** If we are close to the limit, we will schedule an off-thread job to flush ALL the caches. 
	 *  Even if we are not, we schedule one after period. If we are at the limit, we will return 
	 *  false, and the caller should write directly to the underlying store.  */
	public synchronized boolean add(long sizeBlock) {
		/**  Here have a lower threshold, say 90% of maxSize, when it will start a write job, but 
		 * still accept the data. */
	    boolean justStartedPush = false;
		if(this.size + sizeBlock > this.maxSize*lowerThreshold) {
		    pushOffThreadNow();
		    justStartedPush = true;
		}
		//Check max size
		if(this.size + sizeBlock > this.maxSize) {
			// Over the limit, caller must write directly.
			// A delayed write is probably scheduled already. This is not a problem.
			// FIXME maybe we should remove it?
			return false;
		} else {
			this.size += sizeBlock;
			if(!justStartedPush) {
			    // Write everything to disk after the maximum delay (period), unless there is already
			    // a job scheduled to write to disk before that.
			    pushOffThreadDelayed();
			} // Else will be written anyway.
			return true;
		}
	}

    private synchronized void pushOffThreadNow() {
        if(runningJob) return;
        runningJob = true;
        this.ticker.queueTimedJob(new Runnable() {
            @Override
            public void run() {
                try {
                    pushAllCachingStores();
                } finally {
                    runningJob = false;
                }
            }
        }, 0);
    }

	private void pushOffThreadDelayed() {
	    if(queuedJob) return;
	    queuedJob = true;
	    this.ticker.queueTimedJob(new Runnable() {
	        @Override
	        public void run() {
	            synchronized(this) {
	                if(runningJob) return;
	                runningJob = true;
	            }
	            try {
	                pushAllCachingStores();
	            } finally {
	                synchronized(this) {
	                    queuedJob = false;
	                    runningJob = false;
	                }
	            }
	        }
	    }, period);
    }

	void pushAllCachingStores() {
		CachingFreenetStore<?>[] cachingStoresSnapshot = null;
		
		while(true) {
		    // Need to re-check occasionally in case new stores have been added.
	        synchronized (cachingStores) {
	            cachingStoresSnapshot = this.cachingStores.toArray(new CachingFreenetStore<?>[cachingStores.size()]);
	        }
			for(CachingFreenetStore<?> cfs : cachingStoresSnapshot) {
				int k=0;
				while(k < numberOfKeysToWrite) {
					long sizeBlock = cfs.pushLeastRecentlyBlock();
					if(sizeBlock == -1) break;
					synchronized(this) {
						size -= sizeBlock;
						assert(size >= 0); // Break immediately if in unit testing.
						if(size < 0) {
							Logger.error(this, "Cache broken: Size = "+size);
							size = 0;
						}
						if(size == 0) return;
					}
					k++;
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
