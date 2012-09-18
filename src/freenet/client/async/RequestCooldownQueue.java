/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.db4o.ObjectContainer;

import freenet.keys.Key;
import freenet.node.SendableGet;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

/**
 * Queue of keys which have been recently requested, which we have unregistered for a fixed period.
 * They won't be requested for a while, although we still have ULPR subscriptions set up for them.
 *
 * We add to the end, remove from the beginning, and occasionally remove from the middle. It's a
 * circular buffer, we expand it if necessary.
 * @author toad
 */
public class RequestCooldownQueue implements CooldownQueue {

	/** keys which have been put onto the cooldown queue */
	private Key[] keys;
	/** times at which keys will be valid again */
	private long[] times;
	/** clients responsible for the keys */
	private SendableGet[] clients;
	/** count of keys removed from middle i.e. holes */
	int holes;
	/** location of first (chronologically) key */
	int startPtr;
	/** location next key will be put in (may be < startPtr if wrapped around) */
	int endPtr;
	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
				logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
			}
		});
	}

	static final int MIN_SIZE = 128;

	final long cooldownTime;

	RequestCooldownQueue(long cooldownTime) {
		keys = new Key[MIN_SIZE];
		times = new long[MIN_SIZE];
		clients = new SendableGet[MIN_SIZE];
		holes = 0;
		startPtr = 0;
		endPtr = 0;
		this.cooldownTime = cooldownTime;
	}

	/* (non-Javadoc)
	 * @see freenet.client.async.CooldownQueue#add(freenet.keys.Key, freenet.node.SendableGet)
	 */
	@Override
	public synchronized long add(Key key, SendableGet client, ObjectContainer container) {
		long removeTime = System.currentTimeMillis() + cooldownTime;
		if(removeTime < getLastTime()) {
			removeTime = getLastTime();
			Logger.error(this, "CLOCK SKEW DETECTED!!! Attempting to compensate, expect things to break!");
		}
		add(key, client, removeTime);
		return removeTime;
	}

	private synchronized long getLastTime() {
		if(startPtr == endPtr) return -1;
		if(endPtr > 0) return times[endPtr-1];
		return times[times.length-1];
	}

	private synchronized void add(Key key, SendableGet client, long removeTime) {
		if(holes < 0) Logger.error(this, "holes = "+holes+" !!");
		if(logMINOR)
			Logger.minor(this, "Adding key "+key+" client "+client+" remove time "+removeTime+" startPtr="+startPtr+" endPtr="+endPtr+" keys.length="+keys.length);
		int ptr = endPtr;
		if(endPtr > startPtr) {
			if(logMINOR) Logger.minor(this, "endPtr > startPtr");
			if(endPtr == keys.length-1) {
				// Last key
				if(startPtr == 0) {
					// No room
					expandQueue();
					add(key, client, null);
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
				add(key, client, null);
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
		clients[ptr] = client;
		return;
	}

	/* (non-Javadoc)
	 * @see freenet.client.async.CooldownQueue#removeKeyBefore(long)
	 */
	@Override
	public synchronized Object removeKeyBefore(long now, long dontCareAfterMillis, ObjectContainer container, int maxKeys) {
		ArrayList<Key> v = new ArrayList<Key>();
		boolean foundIT = false;
		if(logDEBUG) {
			foundIT = bigLog();
		}
		if(logMINOR)
			Logger.minor(this, "Remove key before "+now+" : startPtr="+startPtr+" endPtr="+endPtr+" holes="+holes+" keys.length="+keys.length);
		if(holes < 0) Logger.error(this, "holes = "+holes+" !!");
		if(foundIT) {
			if(logMINOR) Logger.minor(this, "FOUND IT!"); // FIXME remove
		}
		while(true) {
			if(startPtr == endPtr) {
				if(logMINOR) Logger.minor(this, "No keys queued");
				if(!v.isEmpty())
					return v.toArray(new Key[v.size()]);
				else
					return null;
			}
			long time = times[startPtr];
			Key key = keys[startPtr];
			if(key == null) {
				times[startPtr] = 0;
				clients[startPtr] = null;
				startPtr++;
				holes--;
				if(startPtr == times.length) startPtr = 0;
				if(logMINOR) Logger.minor(this, "Skipped hole");
				continue;
			} else {
				if(time > now) {
					if(logMINOR) Logger.minor(this, "First key is later at time "+time);
					if(!v.isEmpty())
						return v.toArray(new Key[v.size()]);
					else if(time < (now + dontCareAfterMillis))
						return Long.valueOf(time);
					else
						return null;
				}
				times[startPtr] = 0;
				keys[startPtr] = null;
				clients[startPtr] = null;
				startPtr++;
				if(startPtr == times.length) startPtr = 0;
			}
			if(logMINOR) Logger.minor(this, "Returning key "+key);
			v.add(key);
			if(v.size() == maxKeys)
				return v.toArray(new Key[v.size()]);
		}
	}

	private static final String DEBUG_TARGET_URI = "CHK@.../chaosradio_131.mp3";

	/**
	 * Heavy logging and debugging point.
	 * Very useful when debugging certain classes of problems.
	 * @return
	 */
	private boolean bigLog() {
		boolean foundIT = false;
		if(clients[startPtr] != null) {
			ClientRequester cr = clients[startPtr].parent;
			if(cr instanceof ClientGetter) {
				String s = ((ClientGetter)cr).getURI().toShortString();
				if(logMINOR) Logger.minor(this, "client = "+s);
				if(s.equals(DEBUG_TARGET_URI)) {
					foundIT = true;
				}
			}
		}

		Map<String, Integer> countsByShortURI = new HashMap<String, Integer>();
		int nulls = 0;
		int nullClients = 0;
		int notGetter = 0;
		int valid = 0;
		for(int i=0;i<keys.length;i++) {
			if(keys[i] == null) {
				nulls++;
				continue;
			}
			if(clients[i] == null) {
				nullClients++; // Odd...
				continue;
			}
			valid++;
			ClientRequester cr = clients[i].parent;
			if(cr instanceof ClientGetter) {
				String shortURI = ((ClientGetter)cr).getURI().toShortString();
				Integer ctr = countsByShortURI.get(shortURI);
				if(ctr == null) ctr = Integer.valueOf(1);
				else ctr = Integer.valueOf(ctr.intValue()+1);
				countsByShortURI.put(shortURI, ctr);
			} else {
				notGetter++;
			}
		}
//		System.err.println("COOLDOWN QUEUE DUMP:");
//		System.err.println();
//		System.err.println("BY CLIENTS:");
//		for (Map.Entry<String, Integer> en: countsByShortURI.entrySet()) {
//			System.err.println(en.getKey()+" : "+en.getValue());
//		}
//		System.err.println();
//		System.err.println("Nulls:"+nulls);
//		System.err.println("Null clients: "+nullClients);
//		System.err.println("Not a getter: "+notGetter);
//		System.err.println("Valid: "+valid);
//		System.err.println();
		return foundIT;
	}

	/* (non-Javadoc)
	 * @see freenet.client.async.CooldownQueue#removeKey(freenet.keys.Key, freenet.node.SendableGet, long)
	 */
	@Override
	public synchronized boolean removeKey(Key key, SendableGet client, long time, ObjectContainer container) {
		if(time <= 0) return false; // We won't find it.
		if(holes < 0) Logger.error(this, "holes = "+holes+" !!");
		if(logMINOR) Logger.minor(this, "Remove key "+key+" client "+client+" at time "+time+" startPtr="+startPtr+" endPtr="+endPtr+" holes="+holes+" keys.length="+keys.length);
		int idx = -1;
		if(endPtr > startPtr) {
			idx = Arrays.binarySearch(times, startPtr, endPtr, time);
		} else if(endPtr == startPtr) {
			if(logMINOR) Logger.minor(this, "No keys queued");
			return false;
		} else { // endPtr < startPtr
			// FIXME: ARGH! Java doesn't provide binarySearch with from and to!
			if(startPtr != times.length - 1)
				idx = Arrays.binarySearch(times, startPtr, times.length, time);
			if(idx < 0 && startPtr != 0)
				idx = Arrays.binarySearch(times, 0, endPtr, time);
		}
		if(logMINOR) Logger.minor(this, "idx = "+idx);
		if(idx < 0) return false;
		if(keys[idx] == key && clients[idx] == client) {
			keys[idx] = null;
			clients[idx] = null;
			holes++;
			if(logMINOR) Logger.minor(this, "Found (exact)");
			return true;
		}
		// Try backwards first
		int nidx = idx;
		while(true) {
			if(times[nidx] != time) break;
			if(keys[nidx] == key && clients[nidx] == client) {
				keys[nidx] = null;
				clients[nidx] = null;
				holes++;
				if(logMINOR) Logger.minor(this, "Found (backwards)");
				return true;
			}
			if(nidx == startPtr) break;
			nidx--;
			if(nidx == -1) nidx = times.length-1;
		}
		nidx = idx;
		// Now try forwards
		while(true) {
			if(times[nidx] != time) break;
			if(keys[nidx] == key && clients[nidx] == client) {
				keys[nidx] = null;
				clients[nidx] = null;
				holes++;
				if(logMINOR) Logger.minor(this, "Found (forwards)");
				return true;
			}
			if(nidx == endPtr) break;
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
		if(holes < 0) {
			Logger.error(this, "holes = "+holes+" !!");
			holes = 0;
		}
		int newSize = (keys.length - holes) * 2;
		if(newSize < MIN_SIZE) newSize = MIN_SIZE;
		// FIXME reuse the old buffers if it fits
		Key[] newKeys = new Key[newSize];
		long[] newTimes = new long[newSize];
		SendableGet[] newClients = new SendableGet[newSize];
		// Reset startPtr to 0, and remove holes.
		int x = 0;
		long lastTime = -1;
		if(endPtr > startPtr) {
			for(int i=startPtr;i<endPtr;i++) {
				if(keys[i] == null) continue;
				newKeys[x] = keys[i];
				newTimes[x] = times[i];
				newClients[x] = clients[i];
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
				newClients[x] = clients[i];
				if(lastTime > times[i])
					Logger.error(this, "RequestCooldownQueue INCONSISTENCY: times["+i+"] = times[i] but lastTime="+lastTime);
				lastTime = times[i];
				x++;
			}
			for(int i=0;i<endPtr;i++) {
				if(keys[i] == null) continue;
				newKeys[x] = keys[i];
				newTimes[x] = times[i];
				newClients[x] = clients[i];
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
		clients = newClients;
		endPtr = x;
	}

}
