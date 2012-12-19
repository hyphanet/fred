/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Candidate;
import com.db4o.query.Evaluation;
import com.db4o.query.Query;

import freenet.keys.Key;
import freenet.node.SendableGet;
import freenet.support.HexUtil;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

/**
 * Persistable implementation of CooldownQueue. Much simpler than RequestCooldownQueue,
 * and would use much more memory if it wasn't for the database!
 *
 * Creator must call setContainer() and setCooldownTime() before use, after pulling it
 * out of the database.
 * @author toad
 */
// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
public class PersistentCooldownQueue implements CooldownQueue {

	private transient static volatile boolean logMINOR;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	private long cooldownTime;

	/** Cache of items found by removeKeyBefore() that could not be returned.
	 * Kept deactivated to minimise memory usage, but if we have a big
	 * backlog, this can avoid having to re-run the query. */
	private transient LinkedList<PersistentCooldownQueueItem> itemsFromLastTime;

	private static final int KEEP_ITEMS_FROM_LAST_TIME = 1024;

	void setCooldownTime(long time) {
		cooldownTime = time;
		itemsFromLastTime = new LinkedList<PersistentCooldownQueueItem>();
	}

	@Override
	public long add(Key key, SendableGet client, ObjectContainer container) {
		assert(cooldownTime != 0);
		long removeTime = System.currentTimeMillis() + cooldownTime;
		container.activate(key, 5);
		PersistentCooldownQueueItem persistentCooldownQueueItem = new PersistentCooldownQueueItem(client, key.cloneKey(), removeTime, this);
		container.store(persistentCooldownQueueItem);
		return removeTime;
	}

	@Override
	public boolean removeKey(final Key key, final SendableGet client, final long time, ObjectContainer container) {
		boolean found = false;
		final String keyAsBytes = HexUtil.bytesToHex(key.getFullKey());
		Query query = container.query();
		query.constrain(PersistentCooldownQueueItem.class);
		query.descend("keyAsBytes").constrain(keyAsBytes);
		// The result from parent will be huge, and client may be huge too.
		// Don't bother with a join, just check in the evaluation.
//		query.descend("client").constrain(client);
//		query.descend("parent").constrain(this);
		Evaluation eval = new Evaluation() {
			final private static long serialVersionUID = 1537102695504880276L;

			@Override
			public void evaluate(Candidate candidate) {
				PersistentCooldownQueueItem item = (PersistentCooldownQueueItem) candidate.getObject();
				if(item.client != client) {
					candidate.include(false);
					return;
				}
				if(item.parent != PersistentCooldownQueue.this) {
					candidate.include(false);
					return;
				}
				Key k = item.key;
				candidate.objectContainer().activate(k, 5);
				if(k.equals(key))
					candidate.include(true);
				else {
					candidate.include(false);
					candidate.objectContainer().deactivate(k, 5);
				}
			}

		};
		query.constrain(eval);
		ObjectSet<PersistentCooldownQueueItem> results = query.execute();

		while(results.hasNext()) {
			found = true;
			PersistentCooldownQueueItem i = (PersistentCooldownQueueItem) results.next();
			i.delete(container);
			itemsFromLastTime.remove(i);
		}
		return found;
	}

	@Override
	public Object removeKeyBefore(final long now, long dontCareAfterMillis, ObjectContainer container, int maxCount) {
		return removeKeyBefore(now, dontCareAfterMillis, container, maxCount, null);
	}

	public Object removeKeyBefore(final long now, long dontCareAfterMillis, ObjectContainer container, int maxCount, PersistentCooldownQueue altQueue) {
		// Will be called repeatedly until no more keys are returned, so it doesn't
		// matter very much if they're not in order.

		// This query returns bogus results (cooldown items with times in the future).
//		ObjectSet results = container.query(new Predicate() {
//			public boolean match(PersistentCooldownQueueItem persistentCooldownQueueItem) {
//				if(persistentCooldownQueueItem.time >= now) return false;
//				if(persistentCooldownQueueItem.parent != PersistentCooldownQueue.this) return false;
//				return true;
//			}
//		});

		ArrayList<Key> v = null;
		if(!itemsFromLastTime.isEmpty()) {
			if(v == null)
				v = new ArrayList<Key>(Math.min(maxCount, itemsFromLastTime.size()));
			Logger.normal(this, "Overflow handling in cooldown queue: reusing items from last time, now "+itemsFromLastTime.size());
			for(Iterator<PersistentCooldownQueueItem> it = itemsFromLastTime.iterator();it.hasNext() && v.size() < maxCount;) {
				PersistentCooldownQueueItem i = it.next();
				container.activate(i, 1);
				if(i.parent != this && i.parent != altQueue) {
					container.deactivate(i, 1);
					continue;
				}
				if(i.time >= now) {
					container.deactivate(i, 1);
					if(v.isEmpty()) return i.time;
					return v.toArray(new Key[v.size()]);
				}

				container.activate(i.key, 5);
				if(i.client == null || !container.ext().isStored(i.client)) {
					Logger.normal(this, "Client has been removed but not the persistent cooldown queue item: time "+i.time+" for key "+i.key);
				}
				if(i.key == null) {
					Logger.error(this, "Key is null on cooldown queue! i = "+i+" client="+i.client+" key as bytes = "+i.keyAsBytes);
				} else {
					v.add(i.key.cloneKey());
					i.key.removeFrom(container);
				}
				i.delete(container);
				it.remove();
			}
		}
		if(v != null && v.size() == maxCount)
			return v.toArray(new Key[v.size()]);

		// Lets re-code it in SODA.
		long tStart = System.currentTimeMillis();
		Query query = container.query();
		query.constrain(PersistentCooldownQueueItem.class);
		// Don't constrain on parent.
		// parent index is humongous, so we get a huge memory spike, queries take ages.
		// Just check manually.
		query.descend("time").orderAscending().constrain(Long.valueOf(now + dontCareAfterMillis)).smaller();
		ObjectSet<PersistentCooldownQueueItem> results = query.execute();
		if(results.hasNext()) {
			long tEnd = System.currentTimeMillis();
			if(tEnd - tStart > 1000)
				Logger.error(this, "Query took "+(tEnd-tStart)+" for "+results.size());
			else
				if(logMINOR)
					Logger.minor(this, "Query took "+(tEnd-tStart));
			if(v == null)
				v = new ArrayList<Key>(Math.min(maxCount, results.size()));
			while(results.hasNext() && v.size() < maxCount) {
				PersistentCooldownQueueItem i = (PersistentCooldownQueueItem) results.next();
				if(i.parent != this && i.parent != altQueue) {
					continue;
				}
				if(i.time >= now) {
					if(v.isEmpty()) return i.time;
					break;
				}
				container.activate(i.key, 5);
				if(i.client == null || !container.ext().isStored(i.client)) {
					Logger.normal(this, "Client has been removed but not the persistent cooldown queue item: time "+i.time+" for key "+i.key);
				}
				if(i.key == null) {
					Logger.error(this, "Key is null on cooldown queue! i = "+i+" client="+i.client+" key as bytes = "+i.keyAsBytes);
				} else {
					v.add(i.key.cloneKey());
					i.key.removeFrom(container);
				}
				i.delete(container);
			}
			if(!v.isEmpty()) {
				while(results.hasNext() && itemsFromLastTime.size() < KEEP_ITEMS_FROM_LAST_TIME) {
					PersistentCooldownQueueItem i = (PersistentCooldownQueueItem) results.next();
					container.deactivate(i, 1);
					itemsFromLastTime.add(i);
				}
				Logger.normal(this, "Overflow handling in cooldown queue: added items, items from last time now "+itemsFromLastTime.size());
				return v.toArray(new Key[v.size()]);
			}
		} else {
			long tEnd = System.currentTimeMillis();
			if(tEnd - tStart > 1000)
				Logger.error(this, "Query took "+(tEnd-tStart));
			else
				if(logMINOR)
					Logger.minor(this, "Query took "+(tEnd-tStart));
			return null;
		}
		return null;
	}

	public long size(ObjectContainer container) {
		Query query = container.query();
		query.constrain(PersistentCooldownQueueItem.class);
		query.descend("parent").constrain(this).identity();
		ObjectSet<PersistentCooldownQueueItem> results = query.execute();
		return results.size();
	}

}
