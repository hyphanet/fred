package freenet.client.async;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

import com.db4o.ObjectContainer;

import freenet.support.Logger;
import freenet.support.RemoveRandomWithObject;
import freenet.support.Ticker;

/** 
 * When a SendableGet is completed, we add it to the cooldown tracker. We 
 * will not retry that particular SendableGet for 30 minutes. The cooldown
 * tracker is entirely in RAM, so goes away on restart, and this allows us
 * to avoid a huge amount of disk I/O (especially database writes).
 * 
 * The per-key cooldown will be emulated by an extension of the failure 
 * table (FIXME!)
 * 
 * Note that persistent requests and transient requests are handled 
 * differently: for persistent requests we index by the db4o ID of the 
 * object (hence we need to clear the CooldownTracker if we ever do an 
 * online defrag), for transient requests we use a WeakHashMap.
 * 
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 */ 
public class CooldownTracker {
	
	private static volatile boolean logMINOR;

	static {
		Logger.registerClass(CooldownTracker.class);
	}

	/** Persistent CooldownTrackerItem's by Db4o ID */
	private final HashMap<Long, CooldownTrackerItem> trackerItemsPersistent = new HashMap<Long, CooldownTrackerItem>();
	/** Transient CooldownTrackerItem's by HasCooldownTrackerItem */
	private final WeakHashMap<HasCooldownTrackerItem, CooldownTrackerItem> trackerItemsTransient = new WeakHashMap<HasCooldownTrackerItem, CooldownTrackerItem>();
	
	public synchronized CooldownTrackerItem make(HasCooldownTrackerItem parent, boolean persistent, ObjectContainer container) {
		if(persistent) {
			if(!container.ext().isStored(parent)) throw new IllegalArgumentException("Must store first!");
			long uid = container.ext().getID(parent);
			CooldownTrackerItem item = trackerItemsPersistent.get(uid);
			if(item == null)
				trackerItemsPersistent.put(uid, item = parent.makeCooldownTrackerItem());
			return item;
		} else {
			CooldownTrackerItem item = trackerItemsTransient.get(parent);
			if(item == null)
				trackerItemsTransient.put(parent, item = parent.makeCooldownTrackerItem());
			return item;
		}
	}
	
	public synchronized CooldownTrackerItem remove(HasCooldownTrackerItem parent, boolean persistent, ObjectContainer container) {
		if(persistent) {
			if(!container.ext().isStored(parent)) throw new IllegalArgumentException("Must store first!");
			long uid = container.ext().getID(parent);
			return trackerItemsPersistent.remove(uid);
		} else {
			return trackerItemsTransient.remove(parent);
		}
	}
	
	/** Persistent CooldownCacheItem's by Db4o ID */
	private final HashMap<Long, PersistentCooldownCacheItem> cacheItemsPersistent = new HashMap<Long, PersistentCooldownCacheItem>();
	/** Transient CooldownCacheItem's by object */
	private final WeakHashMap<HasCooldownCacheItem, TransientCooldownCacheItem> cacheItemsTransient = new WeakHashMap<HasCooldownCacheItem, TransientCooldownCacheItem>();
	
	/** Check the hierarchical cooldown cache for a specific object.
	 * @param now The current time. Used to update the cache so please don't pass in 
	 * future times!
	 * @return -1 if there is no cache, or a time before which the HasCooldownCacheItem is
	 * guaranteed to have all of its keys in cooldown. */
	public synchronized long getCachedWakeup(HasCooldownCacheItem toCheck, boolean persistent, ObjectContainer container, long now) {
		if(toCheck == null) {
			Logger.error(this, "Asked to check wakeup time for null!", new Exception("error"));
			return -1;
		}
		if(persistent) {
			if(!container.ext().isStored(toCheck)) throw new IllegalArgumentException("Must store first!: "+toCheck);
			long uid = container.ext().getID(toCheck);
			CooldownCacheItem item = cacheItemsPersistent.get(uid);
			if(item == null) return -1;
			if(item.timeValid < now) {
				cacheItemsPersistent.remove(uid);
				return -1;
			}
			return item.timeValid;
		} else {
			CooldownCacheItem item = cacheItemsTransient.get(toCheck);
			if(item == null) return -1;
			if(item.timeValid < now) {
				cacheItemsTransient.remove(toCheck);
				return -1;
			}
			return item.timeValid;
		}
	}

	public synchronized void setCachedWakeup(long wakeupTime, HasCooldownCacheItem toCheck, HasCooldownCacheItem parent, boolean persistent, ObjectContainer container, ClientContext context) {
		setCachedWakeup(wakeupTime, toCheck, parent, persistent, container, context, false);
	}
	
	public void setCachedWakeup(long wakeupTime, HasCooldownCacheItem toCheck, HasCooldownCacheItem parent, boolean persistent, ObjectContainer container, ClientContext context, boolean dontLogOnClearingParents) {
		synchronized(this) {
		if(logMINOR) Logger.minor(this, "Wakeup time "+wakeupTime+" set for "+toCheck+" parent is "+parent);
		if(persistent) {
			if(!container.ext().isStored(toCheck)) throw new IllegalArgumentException("Must store first!");
			long uid = container.ext().getID(toCheck);
			if(parent != null && !container.ext().isStored(parent)) throw new IllegalArgumentException("Must store first!");
			long parentUID = parent == null ? -1 : container.ext().getID(parent);
			PersistentCooldownCacheItem item = cacheItemsPersistent.get(uid);
			if(item == null) {
				cacheItemsPersistent.put(uid, item = new PersistentCooldownCacheItem(wakeupTime, parentUID));
			} else {
				if(item.timeValid < wakeupTime)
					item.timeValid = wakeupTime;
				item.parentID = parentUID;
			}
			if(parentUID != -1) {
				// All items above this should have a wakeup time no later than this.
				while(true) {
					PersistentCooldownCacheItem checkParent = cacheItemsPersistent.get(parentUID);
					if(checkParent == null) break;
					if(checkParent.timeValid < item.timeValid) break;
					else if(checkParent.timeValid > item.timeValid) {
						if(!dontLogOnClearingParents)
							Logger.error(this, "Corrected parent timeValid from "+checkParent.timeValid+" to "+item.timeValid, new Exception("debug"));
						else {
							if(logMINOR) 
								Logger.minor(this, "Corrected parent timeValid from "+checkParent.timeValid+" to "+item.timeValid);
						}
						checkParent.timeValid = item.timeValid;
					}
					parentUID = checkParent.parentID;
					if(parentUID < 0) break;
				}
			}
		} else {
			TransientCooldownCacheItem item = cacheItemsTransient.get(toCheck);
			if(item == null) {
				cacheItemsTransient.put(toCheck, item = new TransientCooldownCacheItem(wakeupTime, parent));
			} else {
				if(item.timeValid < wakeupTime)
					item.timeValid = wakeupTime;
				if(item.parent.get() != parent) {
					if(parent == null)
						item.parent = null;
					else
						item.parent = new WeakReference<HasCooldownCacheItem>(parent);
				}
			}
			if(parent != null) {
				// All items above this should have a wakeup time no later than this.
				while(true) {
					TransientCooldownCacheItem checkParent = cacheItemsTransient.get(parent);
					if(checkParent == null) break;
					if(checkParent.timeValid < item.timeValid) break;
					else if(checkParent.timeValid > item.timeValid) {
						if(!dontLogOnClearingParents)
							Logger.error(this, "Corrected parent timeValid from "+checkParent.timeValid+" to "+item.timeValid, new Exception("debug"));
						else {
							if(logMINOR) 
								Logger.minor(this, "Corrected parent timeValid from "+checkParent.timeValid+" to "+item.timeValid);
						}
						checkParent.timeValid = item.timeValid;
					}
					parent = checkParent.parent.get();
					if(parent == null) break;
				}
			}
		}
		}
		if(toCheck instanceof RemoveRandomWithObject) {
			Object client = ((RemoveRandomWithObject)toCheck).getObject();
			if(client instanceof WantsCooldownCallback) {
				boolean wasActive = true;
				if(persistent) wasActive = container.ext().isActive(client);
				if(!wasActive) container.activate(client, 1);
				((WantsCooldownCallback)client).enterCooldown(wakeupTime, container, context);
				if(!wasActive) container.deactivate(client, 1);
			}
		}
	}
	
	/** The cached item has been completed, failed etc. It should be removed but will 
	 * not affect its ancestors' cached times.
	 * @param toCheck
	 * @param persistent
	 * @param container
	 */
	public synchronized boolean removeCachedWakeup(HasCooldownCacheItem toCheck, boolean persistent, ObjectContainer container) {
		if(persistent) {
			if(!container.ext().isStored(toCheck)) throw new IllegalArgumentException("Must store first!");
			long uid = container.ext().getID(toCheck);
			return cacheItemsPersistent.remove(uid) != null;
		} else {
			return cacheItemsTransient.remove(toCheck) != null;
		}
	}
	
	/** The cached item has become fetchable unexpectedly. It should be cleared along with
	 * all its ancestors.
	 * 
	 * CALLER SHOULD CALL wakeStarter() on the ClientRequestScheduler. We can't do that 
	 * here because we don't know which scheduler to wake and will often be inside locks 
	 * etc.
	 * 
	 * LOCKING: Caller should hold the ClientRequestScheduler lock, or otherwise we
	 * can get nasty race conditions, involving one thread seeing the cooldowns and 
	 * adding a higher one while another clears it (the overlapping case is 
	 * especially bad). Callers of callers should hold as few locks as possible.
	 * @param toCheck
	 * @param persistent
	 * @param container
	 */
	public boolean clearCachedWakeup(HasCooldownCacheItem toCheck, boolean persistent, ObjectContainer container) {
		if(toCheck == null) {
			Logger.error(this, "Clearing cached wakeup for null", new Exception("error"));
			return false;
		}
		if(logMINOR) Logger.minor(this, "Clearing cached wakeup for "+toCheck);
		if(persistent) {
			if(!container.ext().isStored(toCheck)) throw new IllegalArgumentException("Must store first!");
			long uid = container.ext().getID(toCheck);
			if(clearCachedWakeupPersistent(uid)) {
				if(toCheck instanceof RemoveRandomWithObject) {
					Object client = ((RemoveRandomWithObject)toCheck).getObject();
					if(client instanceof WantsCooldownCallback) {
						boolean wasActive = container.ext().isActive(client);
						if(!wasActive) container.activate(client, 1);
						((WantsCooldownCallback)client).clearCooldown(container);
						if(!wasActive) container.deactivate(client, 1);
					}
				}
				return true;
			} else return false;
		} else {
			boolean ret = false;
			synchronized(this) {
				while(true) {
					TransientCooldownCacheItem item = cacheItemsTransient.get(toCheck);
					if(item == null) break;
					if(logMINOR) Logger.minor(this, "Clearing "+toCheck);
					ret = true;
					cacheItemsTransient.remove(toCheck);
					toCheck = item.parent.get();
					if(toCheck == null) break;
					if(logMINOR) Logger.minor(this, "Parent is "+toCheck);
				}
			}
			if(toCheck instanceof RemoveRandomWithObject) {
				Object client = ((RemoveRandomWithObject)toCheck).getObject();
				if(client instanceof WantsCooldownCallback) {
					((WantsCooldownCallback)client).clearCooldown(container);
				}
			}
			return ret;
		}

	}
	
	public synchronized boolean clearCachedWakeupPersistent(Long uid) {
		boolean ret = false;
		while(true) {
			PersistentCooldownCacheItem item = cacheItemsPersistent.get(uid);
			if(item == null) return ret;
			if(logMINOR) Logger.minor(this, "Clearing "+uid);
			ret = true;
			cacheItemsPersistent.remove(uid);
			uid = item.parentID;
			if(uid == -1) return ret;
			if(logMINOR) Logger.minor(this, "Parent is "+uid);
		}
	}


	
	/** Clear expired items from the cache */
	public synchronized void clearExpired(long now) {
		// FIXME more efficient implementation using a queue???
		int removedPersistent = 0;
		Iterator<Map.Entry<Long, PersistentCooldownCacheItem>> it =
			cacheItemsPersistent.entrySet().iterator();
		while(it.hasNext()) {
			Map.Entry<Long, PersistentCooldownCacheItem> item = it.next();
			if(item.getValue().timeValid < now) {
				removedPersistent++;
				it.remove();
			}
		}
		int removedTransient = 0;
		Iterator<Map.Entry<HasCooldownCacheItem, TransientCooldownCacheItem>> it2 =
			cacheItemsTransient.entrySet().iterator();
		while(it2.hasNext()) {
			Map.Entry<HasCooldownCacheItem, TransientCooldownCacheItem> item = it2.next();
			if(item.getValue().timeValid < now) {
				removedTransient++;
				it2.remove();
			}
		}
		if(logMINOR) Logger.minor(this, "Removed "+removedPersistent+" persistent cooldown cache items and "+removedTransient+" transient cooldown cache items");
	}
	
	private static final long MAINTENANCE_PERIOD = 10*60*1000;
	
	public void startMaintenance(final Ticker ticker) {
		ticker.queueTimedJob(new Runnable() {

			@Override
			public void run() {
				clearExpired(System.currentTimeMillis());
				ticker.queueTimedJob(this, MAINTENANCE_PERIOD);
			}
			
		}, MAINTENANCE_PERIOD);
	}

}

class PersistentCooldownCacheItem extends CooldownCacheItem {
	
	public PersistentCooldownCacheItem(long wakeupTime, long parentUID) {
		super(wakeupTime);
		this.parentID = parentUID;
	}

	/** The Db4o ID of the parent object */
	long parentID;
	
}

class TransientCooldownCacheItem extends CooldownCacheItem {
	
	public TransientCooldownCacheItem(long wakeupTime,
			HasCooldownCacheItem parent2) {
		super(wakeupTime);
		this.parent = new WeakReference<HasCooldownCacheItem>(parent2);
	}

	/** A reference to the parent object. Can be null but only if no parent. */
	WeakReference<HasCooldownCacheItem> parent;
	
}