package freenet.support;

/**
 * Token bucket. Can be used for e.g. bandwidth limiting.
 * Tokens are added once per tick.
 */
public class TokenBucket {

	protected long current;
	protected long max;
	protected long timeLastTick;
	protected long nanosPerTick;
	protected long nextWake;
	
	/**
	 * Create a token bucket.
	 * @param max The maximum size of the bucket, in tokens.
	 * @param nanosPerTick The number of nanoseconds between ticks.
	 */
	public TokenBucket(long max, long nanosPerTick, long initialValue) {
		this.max = max;
		this.current = initialValue;
		this.nanosPerTick = nanosPerTick;
		long now = System.currentTimeMillis();
		this.timeLastTick = now * (1000 * 1000);
		nextWake = now;
	}
	
	/**
	 * Either grab a bunch of tokens, or don't. Never block.
	 * @param tokens The number of tokens to grab.
	 * @return True if we could acquire the tokens.
	 */
	public synchronized boolean instantGrab(long tokens) {
		addTokens();
		if(current > tokens) {
			current -= tokens;
			if(current > max) current = max;
			return true;
		} else {
			if(current > max) current = max;
			return false;
		}
	}
	
	/**
	 * Remove tokens, without blocking, even if it causes the balance to go negative.
	 * @param tokens The number of tokens to remove.
	 */
	public synchronized void forceGrab(long tokens) {
		addTokens();
		current -= tokens;
		if(current > max) current = max;
	}
	
	/**
	 * Get the current number of available tokens.
	 */
	public synchronized long count() {
		return current;
	}

	protected long offset() {
		return 0;
	}
	
	public synchronized void blockingGrab(long tokens) {
		if(tokens < max)
			innerBlockingGrab(tokens);
		else {
			for(int i=0;i<tokens;i+=max) {
				innerBlockingGrab(Math.min(tokens, max));
			}
		}
	}
	
	/**
	 * Grab a bunch of tokens. Block if necessary.
	 * @param tokens The number of tokens to grab.
	 */
	public synchronized void innerBlockingGrab(long tokens) {
		Logger.minor(this, "Blocking grab: "+tokens);
		addTokens();
		if(current > max) current = max;
		Logger.minor(this, "current="+current);
		if(current > tokens) {
			current -= tokens;
			return;
		}
		long extra = 0;
		if(current > 0) {
			tokens -= current;
			current = 0;
		} else if(current < 0) {
			extra = -current;
			current = 0;
		}
		long minDelayNS = nanosPerTick * (tokens + extra);
		long minDelayMS = minDelayNS / (1000*1000) + (minDelayNS % (1000*1000) == 0 ? 0 : 1);
		long now = System.currentTimeMillis();
		
		// Schedule between the blockingGrab's.
		
		if(nextWake < now) nextWake = now;
		long wakeAt = (nextWake += minDelayMS);
		while(true) {
			now = System.currentTimeMillis();
			int delay = (int) Math.min(Integer.MAX_VALUE, wakeAt - now);
			if(delay <= 0) break;
			Logger.minor(this, "Waiting "+delay+"ms");
			try {
				wait(delay);
			} catch (InterruptedException e) {
				// Go around the loop again.
			}
		}
		// Remove the tokens, even if we have built up a debt due to forceGrab()s and
		// will therefore go negative. We have paid off the initial debt, and we have
		// paid off the tokens, any more debt is a problem for future blockingGrab's!
		current -= tokens;
	}

	public synchronized void recycle(long tokens) {
		current += tokens;
		if(current > max) current = max;
	}
	
	/**
	 * Change the number of nanos per tick.
	 * @param nanosPerTick The new number of nanos per tick.
	 */
	public synchronized void changeNanosPerTick(long nanosPerTick) {
		// Synchronize up first, using the old nanosPerTick.
		if(nanosPerTick <= 0) throw new IllegalArgumentException();
		addTokens();
		this.nanosPerTick = nanosPerTick;
		if(nanosPerTick < this.nanosPerTick)
			notifyAll();
	}

	public synchronized void changeBucketSize(long newMax) {
		if(newMax <= 0) throw new IllegalArgumentException();
		addTokensNoClip();
		max = newMax;
		if(current > max) current = max;
	}
	
	public synchronized void changeNanosAndBucketSize(long nanosPerTick, long newMax) {
		if(nanosPerTick <= 0) throw new IllegalArgumentException();
		if(newMax <= 0) throw new IllegalArgumentException();
		// Synchronize up first, using the old nanosPerTick.
		addTokensNoClip();
		if(nanosPerTick < this.nanosPerTick)
			notifyAll();
		this.nanosPerTick = nanosPerTick;
		this.max = newMax;
		if(current > max) current = max;
	}
	
	public synchronized void addTokens() {
		addTokensNoClip();
		if(current > max) current = max;
	}
	
	/**
	 * Update the number of tokens according to elapsed time.
	 */
	public synchronized void addTokensNoClip() {
		long add = tokensToAdd();
		current += add;
		timeLastTick += add * nanosPerTick;
		// Deliberately do not clip to size at this point; caller must do this, but it is usually beneficial for the caller to do so.
	}
	
	synchronized long tokensToAdd() {
		long nowNS = System.currentTimeMillis() * (1000 * 1000);
		long nextTick = timeLastTick + nanosPerTick;
		if(nextTick > nowNS) {
			return 0;
		}
		if(nextTick + nanosPerTick > nowNS) {
			timeLastTick = nextTick;
			return 1;
		}
		return (nowNS - nextTick) / nanosPerTick;
	}
}
