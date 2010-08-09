package freenet.client.async;

import java.util.HashMap;
import java.util.WeakHashMap;

import com.db4o.ObjectContainer;

import freenet.node.SendableGet;

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
	
	/** CooldownTrackerItem's by Db4o ID */
	private final HashMap<Long, CooldownTrackerItem> trackerItemsPersistent = new HashMap<Long, CooldownTrackerItem>();
	/** CooldownTrackerItem's by HasCooldownTrackerItem */
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
	
}
