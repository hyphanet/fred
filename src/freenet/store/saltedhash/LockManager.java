/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.store.saltedhash;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import freenet.support.Logger;

/**
 * Lock Manager
 * 
 * Handle locking/unlocking of individual offsets.
 * 
 * @author sdiz
 */
public class LockManager {
	private static boolean logDEBUG;
	private volatile boolean shutdown;
	private Lock entryLock = new ReentrantLock();
	private Map<Long, Condition> lockMap = new HashMap<Long, Condition>();

	LockManager() {
		logDEBUG = Logger.shouldLog(Logger.DEBUG, this);
	}

	/**
	 * Lock the entry
	 * 
	 * This lock is <strong>not</strong> re-entrance. No threads except Cleaner should hold more
	 * then one lock at a time (or deadlock may occur).
	 */
	boolean lockEntry(long offset) {
		if (logDEBUG)
			Logger.debug(this, "try locking " + offset, new Exception());

		try {
			entryLock.lock();
			try {
				do {
					if (shutdown)
						return false;

					Condition lockCond = lockMap.get(offset);
					if (lockCond != null)
						lockCond.await(10, TimeUnit.SECONDS); // 10s for checking shutdown
					else
						break;
				} while (true);
				lockMap.put(offset, entryLock.newCondition());
			} finally {
				entryLock.unlock();
			}
		} catch (InterruptedException e) {
			Logger.error(this, "lock interrupted", e);
			return false;
		}

		if (logDEBUG)
			Logger.debug(this, "locked " + offset, new Exception());
		return true;
	}

	/**
	 * Unlock the entry
	 */
	void unlockEntry(long offset) {
		if (logDEBUG)
			Logger.debug(this, "unlocking " + offset, new Exception("debug"));

		entryLock.lock();
		try {
			Condition cond = lockMap.remove(offset);
			assert cond != null;
			cond.signal();
		} finally {
			entryLock.unlock();
		}
	}

	/**
	 * Shutdown and wait for all entries unlocked
	 */
	void shutdown() {
		shutdown = true;
		entryLock.lock();
		try {
			while (!lockMap.isEmpty()) {
				Condition cond = lockMap.values().iterator().next();
				cond.awaitUninterruptibly();
			}
		} finally {
			entryLock.unlock();
		}
	}
}
