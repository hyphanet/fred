package freenet.crypt;

import freenet.Core;
import freenet.support.Logger;

/**
 * A pool of RandomSource's that distributes incoming calls somewhat evenly
 * over the underlying pool items. Useful for reducing lock congestion
 * situations...
 * 
 * @author Iakin
 */
public class RandomSourcePool extends RandomSource {

	private final RandomSource[] pool;
	private volatile int nextPtr = 0;

	public RandomSourcePool(RandomSource[] pool) {
		this.pool = pool;
		// I have no idea how an element ended up null.
		// But it did!
		for(int i=0;i<pool.length;i++) {
		    if(pool[i] == null)
		        throw new IllegalArgumentException("pool["+i+" of "+
		                pool.length+"] = "+pool[i]);
		}
	}
	public int acceptEntropy(
		EntropySource source,
		long data,
		int entropyGuess) {
	    // Excessive paranoia due to bizarre and probably extremely
	    // uncommon NPE here...
	    while(true) {
	        nextPtr = (nextPtr + 1) % pool.length;
	        RandomSource r = pool[nextPtr];
	        if(r == null) {
	            Core.logger.log(this, "pool["+nextPtr+"] = null!",
	                    new Exception("debug"), Logger.ERROR);
	            continue;
	        }
	        return r.acceptEntropy(source, data, entropyGuess);
	    }
	}

	public int acceptTimerEntropy(EntropySource timer) {
		nextPtr = (nextPtr + 1) % pool.length;
		return pool[nextPtr].acceptTimerEntropy(timer);
	}

	public void close() {
		for (int i = 0; i < pool.length; i++) {
			pool[i].close();
			pool[i] = null;
		}
	}
}
