package freenet.client.async;

import java.util.ArrayList;
import java.util.List;

import freenet.support.Logger;
import freenet.support.RemoveRandomWithObject;

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
	public void setCachedWakeup(long wakeupTime, RequestSelectionTreeNode toCheck, ClientContext context) {
        List<WantsCooldownCallback> maybeWantCallbacks = null;
		synchronized(this) {
		if(logMINOR) Logger.minor(this, "Wakeup time "+wakeupTime+" set for "+toCheck);
		toCheck = toCheck.getParentGrabArray();
		// All items above this should have a wakeup time no later than this.
		while(true) {
		    if(toCheck == null) break;
		    if(toCheck.reduceCooldownTime(wakeupTime)) break;
		    if(toCheck instanceof RemoveRandomWithObject) {
		        Object client = ((RemoveRandomWithObject)toCheck).getObject();
		        if(client instanceof WantsCooldownCallback) {
		            if(maybeWantCallbacks == null) maybeWantCallbacks = new ArrayList<WantsCooldownCallback>();
		            maybeWantCallbacks.add((WantsCooldownCallback)client);
		        }
		    }
		    toCheck = toCheck.getParentGrabArray();
		}
		}
		if(maybeWantCallbacks != null) {
		    for(WantsCooldownCallback cb : maybeWantCallbacks) {
				cb.enterCooldown(wakeupTime, context);
			}
		}
	}
	
}
