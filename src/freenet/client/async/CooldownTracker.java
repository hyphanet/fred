package freenet.client.async;

import static java.util.concurrent.TimeUnit.MINUTES;

import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

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

	/** Transient CooldownCacheItem's by object */
	private final WeakHashMap<RequestSelectionTreeNode, TransientCooldownCacheItem> cacheItemsTransient = new WeakHashMap<RequestSelectionTreeNode, TransientCooldownCacheItem>();
	
	/** Check the hierarchical cooldown cache for a specific object.
	 * @param now The current time. Used to update the cache so please don't pass in 
	 * future times!
	 * @return -1 if there is no cache, or a time before which the RequestSelectionTreeNode is
	 * guaranteed to have all of its keys in cooldown. */
	public synchronized long getCachedWakeup(RequestSelectionTreeNode toCheck, long now) {
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

	public synchronized void setCachedWakeup(long wakeupTime, RequestSelectionTreeNode toCheck, RequestSelectionTreeNode parent, ClientContext context) {
		setCachedWakeup(wakeupTime, toCheck, parent, context, false);
	}
	
	/** Must be called when a request goes into cooldown, or is not fetchable because all of its
	 * requests are running. Recording the parent is essential so that when the request wakes up, 
	 * its parents in the fetch structure will also be woken up so that it can actually be tried 
	 * again.
	 * @param wakeupTime When the request will become fetchable again (Long.MAX_VALUE for "never", 
	 * usually meaning "when a request finishes").
	 * @param toCheck The request going into cooldown.
	 * @param parent The parent of the request going into cooldown.
	 * @param context
	 * @param dontLogOnClearingParents
	 */
	public void setCachedWakeup(long wakeupTime, RequestSelectionTreeNode toCheck, RequestSelectionTreeNode parent, ClientContext context, boolean dontLogOnClearingParents) {
		synchronized(this) {
		if(logMINOR) Logger.minor(this, "Wakeup time "+wakeupTime+" set for "+toCheck+" parent is "+parent);
		TransientCooldownCacheItem item = cacheItemsTransient.get(toCheck);
		if(item == null) {
		    cacheItemsTransient.put(toCheck, item = new TransientCooldownCacheItem(toCheck, wakeupTime));
		} else {
		    if(item.timeValid < wakeupTime)
		        item.timeValid = wakeupTime;
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
		        parent = checkParent.node.getParentGrabArray();
		        if(parent == null) break;
		    }
		}
		}
		if(toCheck instanceof RemoveRandomWithObject) {
			Object client = ((RemoveRandomWithObject)toCheck).getObject();
			if(client instanceof WantsCooldownCallback) {
				((WantsCooldownCallback)client).enterCooldown(wakeupTime, context);
			}
		}
	}
	
	/** The cached item has been completed, failed etc. It should be removed but will 
	 * not affect its ancestors' cached times.
	 * @param toCheck
	 * @param persistent
	 * @param container
	 */
	public synchronized boolean removeCachedWakeup(RequestSelectionTreeNode toCheck) {
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
	public boolean clearCachedWakeup(RequestSelectionTreeNode toCheck) {
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
		        toCheck = item.node.getParentGrabArray();
		        if(toCheck == null) break;
		        if(logMINOR) Logger.minor(this, "Parent is "+toCheck);
		    }
		}
		if(toCheck instanceof RemoveRandomWithObject) {
		    Object client = ((RemoveRandomWithObject)toCheck).getObject();
		    if(client instanceof WantsCooldownCallback) {
		        ((WantsCooldownCallback)client).clearCooldown();
		    }
		}
		return ret;
	}
	
	/** Clear expired items from the cache */
	public synchronized void clearExpired(long now) {
		// FIXME more efficient implementation using a queue???
		int removedPersistent = 0;
		int removedTransient = 0;
		Iterator<Map.Entry<RequestSelectionTreeNode, TransientCooldownCacheItem>> it2 =
			cacheItemsTransient.entrySet().iterator();
		while(it2.hasNext()) {
			Map.Entry<RequestSelectionTreeNode, TransientCooldownCacheItem> item = it2.next();
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
	
	public TransientCooldownCacheItem(RequestSelectionTreeNode node, long wakeupTime) {
		super(wakeupTime);
		this.node = node;
	}
	
	final RequestSelectionTreeNode node;

}