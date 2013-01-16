package freenet.support;

import freenet.support.Logger.LogLevel;

/**
 * Token bucket. Can be used for e.g. bandwidth limiting.
 * Tokens are added once per tick.
 */
public class TokenBucket {

	private static boolean logMINOR;
	static {
		LoggerHook.registerClass(TokenBucket.class);
	}
	protected long current;
	protected long max;
	protected long timeLastTick;
	protected long nanosPerTick;
	
	/**
	 * Create a token bucket.
	 * @param max The maximum size of the bucket, in tokens.
	 * @param nanosPerTick The number of nanoseconds between ticks.
	 */
	public TokenBucket(long max, long nanosPerTick, long initialValue) {
		this.max = max;
		this.current = initialValue;
		if(current > max) {
			Logger.error(this, "initial value ("+current+") > max ("+max+") in "+this, new Exception("error"));
			current = max;
		}
		this.nanosPerTick = nanosPerTick;
		long now = System.currentTimeMillis();
		this.timeLastTick = now * (1000 * 1000);
		if(nanosPerTick <= 0) throw new IllegalArgumentException();
		if(max <= 0) throw new IllegalArgumentException();
	}
	
	/**
	 * Either grab a bunch of tokens, or don't. Never block.
	 * @param tokens The number of tokens to grab.
	 * @return True if we could acquire the tokens.
	 */
	public synchronized boolean instantGrab(long tokens) {
		if(tokens < 0) throw new IllegalArgumentException("Can't grab negative tokens: "+tokens);
		if(logMINOR)
			Logger.minor(this, "instant grab: "+tokens+" current="+current+" max="+max);
		addTokens();
		if(logMINOR)
			Logger.minor(this, "instant grab: "+tokens+" current="+current+" max="+max);
		if(current >= tokens) {
			current -= tokens;
			return true;
		} else {
			return false;
		}
	}
	
	/**
	 * Try to grab some tokens; if there aren't enough, grab all of them. Never block.
	 * @param tokens The number of tokens to grab.
	 * @return The number of tokens grabbed.
	 */
	public synchronized long partialInstantGrab(long tokens) {
		if(tokens < 0) throw new IllegalArgumentException("Can't grab negative tokens: "+tokens);
		if(logMINOR)
			Logger.minor(this, "instant grab: "+tokens+" current="+current+" max="+max);
		addTokens();
		if(logMINOR)
			Logger.minor(this, "instant grab: "+tokens+" current="+current+" max="+max);
		if(current >= tokens) {
			current -= tokens;
			return tokens;
		} else {
			tokens = current;
			current = 0;
			return tokens;
		}
	}
	
	/**
	 * Remove tokens, without blocking, even if it causes the balance to go negative.
	 * @param tokens The number of tokens to remove.
	 */
	public synchronized void forceGrab(long tokens) {
		if(tokens < 0) throw new IllegalArgumentException("Can't grab negative tokens: "+tokens);
		if(logMINOR) Logger.minor(this, "forceGrab("+tokens+")");
		addTokens();
		current -= tokens;
		if(logMINOR) Logger.minor(this, "Removed tokens, balance now "+current);
	}
	
	public synchronized long count() {
		return current;
	}
	
	/**
	 * Get the current number of available tokens.
	 */
	public synchronized long getCount() {
		addTokens();
		return current;
	}

	protected long offset() {
		return 0;
	}
	
	public synchronized void blockingGrab(long tokens) {
		if(tokens < 0) throw new IllegalArgumentException("Can't grab negative tokens: "+tokens);
		logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
		if(logMINOR) Logger.minor(this, "Blocking grab: "+tokens);
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
		if(tokens < 0) throw new IllegalArgumentException("Can't grab negative tokens: "+tokens);
		if(logMINOR) Logger.minor(this, "Inner blocking grab: "+tokens);
		addTokens();
		if(logMINOR) Logger.minor(this, "current="+current);
		
		current -= tokens;
		
		if(current >= 0) {
			if(logMINOR) Logger.minor(this, "Got tokens instantly, current="+current);
			return;
		} else {
			if(logMINOR) Logger.minor(this, "Blocking grab removed tokens, current="+current+" - will have to wait because negative...");
		}
		
		long minDelayNS = nanosPerTick * (-current);
		long minDelayMS = (minDelayNS + 1000*1000 - 1) / (1000*1000);
		long now = System.currentTimeMillis();
		long wakeAt = now + minDelayMS;
		
		if(logMINOR) Logger.minor(this, "Waking in "+minDelayMS+" millis");
		
		while(true) {
			now = System.currentTimeMillis();
			int delay = (int) Math.min(Integer.MAX_VALUE, wakeAt - now);
			if(delay <= 0) break;
			if(logMINOR) Logger.minor(this, "Waiting "+delay+"ms");
			try {
				wait(delay);
			} catch (InterruptedException e) {
				// Go around the loop again.
			}
		}
		if(logMINOR) Logger.minor(this, "Blocking grab finished: current="+current);
	}

	public synchronized void recycle(long tokens) {
		if(tokens < 0) throw new IllegalArgumentException("Can't recycle negative tokens: "+tokens);
		current += tokens;
		if(current > max) current = max;
	}
	
	/**
	 * Change the number of nanos per tick.
	 * @param nanosPerTick The new number of nanos per tick.
	 */
	public synchronized void changeNanosPerTick(long nanosPerTick) {
		if(nanosPerTick <= 0) throw new IllegalArgumentException();
		// Synchronize up first, using the old nanosPerTick.
		addTokens();
		this.nanosPerTick = nanosPerTick;
		if(nanosPerTick < this.nanosPerTick)
			notifyAll();
	}

	public synchronized void changeBucketSize(long newMax) {
		if(newMax <= 0) throw new IllegalArgumentException();
		max = newMax;
		addTokens();
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
		if(logMINOR)
			Logger.minor(this, "addTokens: Clipped, current="+current);
	}
	
	/**
	 * Update the number of tokens according to elapsed time.
	 */
	public synchronized void addTokensNoClip() {
		long add = tokensToAdd();
		current += add;
		timeLastTick += add * nanosPerTick;
		if(logMINOR)
			Logger.minor(this, "addTokensNoClip: Added "+add+" tokens, current="+current);
		// Deliberately do not clip to size at this point; caller must do this, but it is usually beneficial for the caller to do so.
	}
	
	synchronized long tokensToAdd() {
		long nowNS = System.currentTimeMillis() * (1000 * 1000);
		if(timeLastTick > nowNS) {
			System.err.println("CLOCK SKEW DETECTED! CLOCK WENT BACKWARDS BY AT LEAST "+TimeUtil.formatTime((timeLastTick - nowNS)/(1000*1000), 2, true));
			System.err.println("FREENET WILL BREAK SEVERELY IF THIS KEEPS HAPPENING!");
			Logger.error(this, "CLOCK SKEW DETECTED! CLOCK WENT BACKWARDS BY AT LEAST "+TimeUtil.formatTime((timeLastTick - nowNS)/(1000*1000), 2, true));
			timeLastTick = nowNS;
			return 0;
		}
		long nextTick = timeLastTick + nanosPerTick;
		if(nextTick > nowNS) {
			return 0;
		}
		if(nextTick + nanosPerTick > nowNS) {
			return 1;
		}
		return (nowNS - nextTick) / nanosPerTick;
	}
	
	public synchronized long getNanosPerTick() {
		return nanosPerTick;
	}
}
