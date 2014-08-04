package freenet.client.async;

import static java.util.concurrent.TimeUnit.MINUTES;

import java.lang.ref.WeakReference;
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

	/** CooldownTrackerItem's by HasCooldownTrackerItem */
	private final WeakHashMap<HasCooldownTrackerItem, CooldownTrackerItem> trackerItemsTransient = new WeakHashMap<HasCooldownTrackerItem, CooldownTrackerItem>();
	
	public synchronized CooldownTrackerItem make(HasCooldownTrackerItem parent) {
	    CooldownTrackerItem item = trackerItemsTransient.get(parent);
	    if(item == null)
	        trackerItemsTransient.put(parent, item = parent.makeCooldownTrackerItem());
	    return item;
	}
	
	public synchronized CooldownTrackerItem remove(HasCooldownTrackerItem parent) {
	    return trackerItemsTransient.remove(parent);
	}
	
	/** Transient CooldownCacheItem's by object */
	private final WeakHashMap<HasCooldownCacheItem, TransientCooldownCacheItem> cacheItemsTransient = new WeakHashMap<HasCooldownCacheItem, TransientCooldownCacheItem>();
	
	/** Check the hierarchical cooldown cache for a specific object.
	 * @param now The current time. Used to update the cache so please don't pass in 
	 * future times!
	 * @return -1 if there is no cache, or a time before which the HasCooldownCacheItem is
	 * guaranteed to have all of its keys in cooldown. */
	public synchronized long getCachedWakeup(HasCooldownCacheItem toCheck, long now) {
		if(toCheck == null) {
			Logger.error(this, "Asked to check wakeup time for null!", new Exception("error"));
			return -1;
		}
		CooldownCacheItem item = cacheItemsTransient.get(toCheck);
		if(item == null) return -1;
		if(item.timeValid < now) {
		    cacheItemsTransient.remove(toCheck);
		    return -1;
		}
		return item.timeValid;
	}

	public synchronized void setCachedWakeup(long wakeupTime, HasCooldownCacheItem toCheck, HasCooldownCacheItem parent, ClientContext context) {
		setCachedWakeup(wakeupTime, toCheck, parent, context, false);
	}
	
	public void setCachedWakeup(long wakeupTime, HasCooldownCacheItem toCheck, HasCooldownCacheItem parent, ClientContext context, boolean dontLogOnClearingParents) {
		synchronized(this) {
		if(logMINOR) Logger.minor(this, "Wakeup time "+wakeupTime+" set for "+toCheck+" parent is "+parent);
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
		if(toCheck instanceof RemoveRandomWithObject) {
			Object client = ((RemoveRandomWithObject)toCheck).getObject();
			if(client instanceof WantsCooldownCallback) {
				((WantsCooldownCallback)client).enterCooldown(wakeupTime, null, context);
			}
		}
	}
	
	/** The cached item has been completed, failed etc. It should be removed but will 
	 * not affect its ancestors' cached times.
	 * @param toCheck
	 * @param persistent
	 * @param container
	 */
	public synchronized boolean removeCachedWakeup(HasCooldownCacheItem toCheck) {
	    return cacheItemsTransient.remove(toCheck) != null;
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
	public boolean clearCachedWakeup(HasCooldownCacheItem toCheck) {
		if(toCheck == null) {
			Logger.error(this, "Clearing cached wakeup for null", new Exception("error"));
			return false;
		}
		if(logMINOR) Logger.minor(this, "Clearing cached wakeup for "+toCheck);
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
		        ((WantsCooldownCallback)client).clearCooldown(null);
		    }
		}
		return ret;
	}
	
	/** Clear expired items from the cache */
	public synchronized void clearExpired(long now) {
		// FIXME more efficient implementation using a queue???
		int removedPersistent = 0;
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
	
	private static final long MAINTENANCE_PERIOD = MINUTES.toMillis(10);
	
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

class TransientCooldownCacheItem extends CooldownCacheItem {
	
	public TransientCooldownCacheItem(long wakeupTime,
			HasCooldownCacheItem parent2) {
		super(wakeupTime);
		this.parent = new WeakReference<HasCooldownCacheItem>(parent2);
	}

	/** A reference to the parent object. Can be null but only if no parent. */
	WeakReference<HasCooldownCacheItem> parent;
	
}