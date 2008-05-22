/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.ArrayList;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Predicate;

import freenet.keys.Key;
import freenet.node.SendableGet;

/**
 * Persistable implementation of CooldownQueue. Much simpler than RequestCooldownQueue,
 * and would use much more memory if it wasn't for the database!
 * 
 * Creator must call setContainer() and setCooldownTime() before use, after pulling it 
 * out of the database.
 * @author toad
 */
public class PersistentCooldownQueue implements CooldownQueue {
	
	private long cooldownTime;

	private static class Item {
		final SendableGet client;
		final Key key;
		final long time;
		final PersistentCooldownQueue parent;
		
		Item(SendableGet client, Key key, long time, PersistentCooldownQueue parent) {
			this.client = client;
			this.key = key;
			this.time = time;
			this.parent = parent;
		}
	}
	
	void setCooldownTime(long time) {
		cooldownTime = time;
	}

	public long add(Key key, SendableGet client, ObjectContainer container) {
		assert(cooldownTime != 0);
		long removeTime = System.currentTimeMillis() + cooldownTime;
		Item item = new Item(client, key, removeTime, this);
		container.set(item);
		return removeTime;
	}

	public boolean removeKey(final Key key, final SendableGet client, final long time, ObjectContainer container) {
		boolean found = false;
		ObjectSet results = container.query(new Predicate() {
			public boolean match(Item item) {
				if(item.parent != PersistentCooldownQueue.this) return false;
				if(item.key != key) return false;
				if(item.client != client) return false;
				return true;
				// Ignore time
			}
		});
		while(results.hasNext()) {
			found = true;
			Item i = (Item) results.next();
			container.delete(i);
		}
		return found;
	}

	public Key[] removeKeyBefore(final long now, ObjectContainer container, int maxCount) {
		// Will be called repeatedly until no more keys are returned, so it doesn't
		// matter very much if they're not in order.
		ObjectSet results = container.query(new Predicate() {
			public boolean match(Item item) {
				if(item.parent != PersistentCooldownQueue.this) return false;
				if(item.time > now) return false;
				return true;
			}
		});
		if(results.hasNext()) {
			ArrayList v = new ArrayList(Math.min(maxCount, results.size()));
			while(results.hasNext() && v.size() < maxCount) {
				Item i = (Item) results.next();
				v.add(i.key);
			}
			return (Key[]) v.toArray(new Key[v.size()]);
		} else
			return null;
	}

}
