/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import freenet.keys.Key;
import freenet.support.Fields;
import freenet.support.Logger;

/**
 * Queue of keys which have been recently requested, which we have unregistered for a fixed period.
 * They won't be requested for a while, although we still have ULPR subscriptions set up for them.
 * 
 * We add to the end, remove from the beginning, and occasionally remove from the middle. It's a
 * circular buffer, we expand it if necessary.
 * @author toad
 */
public class RequestCooldownQueue {

	/** keys which have been put onto the cooldown queue */ 
	private Key[] keys;
	/** times at which keys will be valid again */
	private long[] times;
	/** count of keys removed from middle i.e. holes */
	int holes;
	/** location of first (chronologically) key */
	int startPtr;
	/** location of last key (may be < startPtr if wrapped around) */
	int endPtr;
	static boolean logMINOR;
	
	static final int MIN_SIZE = 2;
	
	final long cooldownTime;

	RequestCooldownQueue(long cooldownTime) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		keys = new Key[MIN_SIZE];
		times = new long[MIN_SIZE];
		holes = 0;
		startPtr = 0;
		endPtr = 0;
		this.cooldownTime = cooldownTime;
	}
	
	/**
	 * Add a key to the end of the queue. Returns the time at which it will be valid again.
	 */
	synchronized long add(Key key) {
		long removeTime = System.currentTimeMillis() + cooldownTime;
		if(removeTime < getLastTime()) {
			removeTime = getLastTime();
			Logger.error(this, "CLOCK SKEW DETECTED!!! Attempting to compensate, expect things to break!");
		}
		add(key, removeTime);
		return removeTime;
	}
	
	private synchronized long getLastTime() {
		if(startPtr == endPtr) return -1;
		if(endPtr > 0) return times[endPtr-1];
		return times[times.length-1];
	}

	private synchronized void add(Key key, long removeTime) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR)
			Logger.minor(this, "Adding key "+key+" remove time "+removeTime+" startPtr="+startPtr+" endPtr="+endPtr+" keys.length="+keys.length);
		int ptr = endPtr;
		if(endPtr > startPtr) {
			if(logMINOR) Logger.minor(this, "endPtr > startPtr");
			if(endPtr == keys.length-1) {
				// Last key
				if(startPtr == 0) {
					// No room
					expandQueue();
					add(key);
					return;
				} else {
					// Wrap around
					endPtr = 0;
				}
			} else {
				endPtr++;
			}
		} else if(endPtr < startPtr){
			if(logMINOR) Logger.minor(this, "endPtr < startPtr");
			if(endPtr == startPtr - 1) {
				expandQueue();
				add(key);
				return;
			} else {
				endPtr++;
			}
		} else /* endPtr == startPtr : nothing queued */ {
			if(logMINOR) Logger.minor(this, "endPtr == startPtr");
			endPtr = 1;
			startPtr = 0;
			ptr = 0;
		}
		if(logMINOR) Logger.minor(this, "Added at "+ptr+" startPtr="+startPtr+" endPtr="+endPtr);
		keys[ptr] = key;
		times[ptr] = removeTime;
		return;
	}

	/**
	 * Remove a key whose cooldown time has passed.
	 * @return Either a Key or null if no keys have passed their cooldown time.
	 */
	synchronized Key removeKeyBefore(long now) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR)
			Logger.minor(this, "Remove key before "+now+" : startPtr="+startPtr+" endPtr="+endPtr+" holes="+holes);
		while(true) {
			if(startPtr == endPtr) {
				if(logMINOR) Logger.minor(this, "No keys queued");
				return null;
			}
			long time = times[startPtr];
			Key key = keys[startPtr];
			if(key == null) {
				times[startPtr] = 0;
				startPtr++;
				holes--;
				if(startPtr == times.length) startPtr = 0;
				if(logMINOR) Logger.minor(this, "Skipped hole");
				continue;
			} else {
				if(time > now) {
					if(logMINOR) Logger.minor(this, "First key is later at time "+time);
					return null;
				}
				times[startPtr] = 0;
				keys[startPtr] = null;
				startPtr++;
				if(startPtr == times.length) startPtr = 0;
			}
			if(logMINOR) Logger.minor(this, "Returning key "+key);
			return key;
		}
	}
	
	/**
	 * @return True if the key was found.
	 */
	synchronized boolean removeKey(Key key, long time) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "Remove key "+key+" at time "+time+" startPtr="+startPtr+" endPtr="+endPtr+" holes="+holes);
		int idx = -1;
		if(endPtr > startPtr) {
			idx = Fields.binarySearch(times, time, startPtr, endPtr);
		} else if(endPtr == startPtr) {
			if(logMINOR) Logger.minor(this, "No keys queued");
			return false;
		} else { // endPtr < startPtr
			// FIXME: ARGH! Java doesn't provide binarySearch with from and to!
			if(startPtr != times.length - 1)
				idx = Fields.binarySearch(times, time, startPtr, times.length - 1);
			if(idx < 0 && startPtr != 0)
				idx = Fields.binarySearch(times, time, 0, endPtr);
		}
		if(logMINOR) Logger.minor(this, "idx = "+idx);
		if(idx < 0) return false;
		if(keys[idx] == key) {
			keys[idx] = null;
			if(logMINOR) Logger.minor(this, "Found (exact)");
			return true;
		}
		// Try backwards first
		int nidx = idx;
		while(true) {
			if(times[nidx] != time) break;
			if(keys[nidx] == key) {
				keys[nidx] = null;
				if(logMINOR) Logger.minor(this, "Found (backwards)");
				return true;
			}
			if(nidx == startPtr) break;
			nidx--;
			if(nidx == -1) nidx = times.length;
		}
		nidx = idx;
		// Now try forwards
		while(true) {
			if(times[nidx] != time) break;
			if(keys[nidx] == key) {
				keys[nidx] = null;
				if(logMINOR) Logger.minor(this, "Found (forwards)");
				return true;
			}
			if(nidx == startPtr) break;
			nidx++;
			if(nidx == times.length) nidx = 0;
		}
		if(logMINOR) Logger.minor(this, "Not found");
		return false;
	}

	/**
	 * Allocate a new queue, and compact while copying.
	 */
	private synchronized void expandQueue() {
		if(logMINOR) Logger.minor(this, "Expanding queue");
		int newSize = (keys.length - holes) * 2;
		// FIXME reuse the old buffers if it fits
		Key[] newKeys = new Key[newSize];
		long[] newTimes = new long[newSize];
		// Reset startPtr to 0, and remove holes.
		int x = 0;
		long lastTime = -1;
		if(endPtr > startPtr) {
			for(int i=startPtr;i<endPtr;i++) {
				if(keys[i] == null) continue;
				newKeys[x] = keys[i];
				newTimes[x] = times[i];
				if(lastTime > times[i])
					Logger.error(this, "RequestCooldownQueue INCONSISTENCY: times["+i+"] = times[i] but lastTime="+lastTime);
				lastTime = times[i];
				x++;
			}
		} else if(endPtr < startPtr) {
			for(int i=startPtr;i<keys.length;i++) {
				if(keys[i] == null) continue;
				newKeys[x] = keys[i];
				newTimes[x] = times[i];
				if(lastTime > times[i])
					Logger.error(this, "RequestCooldownQueue INCONSISTENCY: times["+i+"] = times[i] but lastTime="+lastTime);
				lastTime = times[i];
				x++;
			}
			for(int i=0;i<endPtr;i++) {
				if(keys[i] == null) continue;
				newKeys[x] = keys[i];
				newTimes[x] = times[i];
				if(lastTime > times[i])
					Logger.error(this, "RequestCooldownQueue INCONSISTENCY: times["+i+"] = times[i] but lastTime="+lastTime);
				lastTime = times[i];
				x++;
			}
		} else /* endPtr == startPtr */ {
			Logger.error(this, "RequestCooldownQueue: expandQueue() called with endPtr == startPtr == "+startPtr+" !!");
			return;
		}
		holes = 0;
		startPtr = 0;
		keys = newKeys;
		times = newTimes;
		endPtr = x;
	}

}
