package freenet.support;

/**
 * A TokenBucket variant which provides the following constraint:
 * Forced token grabs may not use more than M tokens out of the total N.
 * 
 * In other words:
 * A classic token bucket keeps a counter, "current", which must not exceed the max, to
 * which we add one token every few millis.
 * 
 * We also keep another counter, unblockableCount, with a separate maximum, 
 * unblockableMax. Whenever a token is added to current by the clock, one is removed 
 * from unblockableCount (but only if it is >0). Whenever an unblockable request to
 * remove tokens comes in, after we update unblockableCount, we calculate how many 
 * tokens we can add to unblockableCount without exceeding the maximum, and only allow
 * that many to be removed from counter.
 */
public class DoubleTokenBucket extends TokenBucket {
	
	private long maxForced;
	private long curForced;
	
	/**
	 * Create a DoubleTokenBucket.
	 * @param max The maximum size of the bucket, in tokens.
	 * @param nanosPerTick The number of nanoseconds between ticks.
	 * 
	 */
	public DoubleTokenBucket(long max, long nanosPerTick, long initialValue, long maxForced) {
		super(max, nanosPerTick, initialValue);
		this.maxForced = maxForced;
		this.curForced = 0;
	}
	
	// instantGrab is unchanged.
	
	// Major changes to forceGrab ! This is where it happens.
	
	public synchronized void forceGrab(long tokens) {
		addTokens();
		long thisMax = maxForced - curForced;
		if(tokens > thisMax) tokens = thisMax;
		curForced += tokens;
		current -= tokens;
		if(current > max) current = max;
		if(curForced > maxForced) curForced = maxForced;
	}
	
	// blockingGrab is unchanged
	
	public synchronized void changeSizeOfBuckets(long newMax, long newMaxForced) {
		changeBucketSize(newMax);
		this.maxForced = newMaxForced;
		if(curForced > maxForced) curForced = maxForced;
	}
	
	public synchronized void addTokens() {
		long add = tokensToAdd();
		current += add;
		curForced -= add;
		if(curForced < 0) curForced = 0;
		timeLastTick += add * nanosPerTick;
	}
	
}
