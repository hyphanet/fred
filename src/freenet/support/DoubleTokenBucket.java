/*
  DoubleTokenBucket.java / Freenet
  Copyright (C) 2005-2006 The Free Network project
  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

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
	private static boolean logMINOR;
	
	/**
	 * Create a DoubleTokenBucket.
	 * @param max The maximum size of the bucket, in tokens.
	 * @param nanosPerTick The number of nanoseconds between ticks.
	 * 
	 */
	public DoubleTokenBucket(long max, long nanosPerTick, long initialValue, long maxForced) {
		super(max, nanosPerTick, initialValue);
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR)
			Logger.minor(this, "Max: "+max+" nanosPerTick: "+nanosPerTick+" initialValue: "+initialValue+" maxForced: "+maxForced);
		this.maxForced = maxForced;
		this.curForced = 0;
	}
	
	// instantGrab is unchanged.
	
	// Major changes to forceGrab ! This is where it happens.
	
	public synchronized void forceGrab(long tokens) {
		addTokens();
		long thisMax = maxForced - curForced;
		if(tokens > thisMax) {
			if(logMINOR) Logger.minor(this, "Limiting force-grab to "+thisMax+" tokens was "+tokens);
			tokens = thisMax;
		}
		curForced += tokens;
		current -= tokens;
		if(curForced > maxForced) {
			curForced = maxForced;
		}
		if(logMINOR) Logger.minor(this, "Force-Grabbed "+tokens+" current="+current+" forced="+curForced);
	}
	
	// blockingGrab is unchanged
	
	public synchronized void changeSizeOfBuckets(long newMax, long newMaxForced) {
		changeBucketSize(newMax);
		this.maxForced = newMaxForced;
		if(curForced > maxForced) curForced = maxForced;
	}
	
	public synchronized void changeNanosAndBucketSizes(long nanos, long newMax, long newMaxForced) {
		// FIXME maybe should be combined
		changeSizeOfBuckets(newMax, newMaxForced);
		changeNanosPerTick(nanos);
	}
	
	public synchronized void addTokensNoClip() {
		long add = tokensToAdd();
		current += add;
		curForced -= add;
		if(curForced < 0) curForced = 0;
		timeLastTick += add * nanosPerTick;
		if(logMINOR) Logger.minor(this, "Added "+add+" tokens current="+current+" forced="+curForced);
	}

	public synchronized void addTokens() {
		if(logMINOR) Logger.minor(this, "current="+current+" forced="+curForced);
		addTokensNoClip();
		if(curForced > maxForced) curForced = maxForced;
		if(current > max) current = max;
	}
	
	public synchronized long getSize() {
		return max;
	}

	protected synchronized long offset() {
		return curForced;
	}
	
}
