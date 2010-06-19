/**
 * 
 */
package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.keys.Key;
import freenet.node.SendableGet;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
public class PersistentCooldownQueueItem {
	final SendableGet client;
	final Key key;
	/** Same trick as we use on PendingKeyItem. Necessary because db4o doesn't
	 * index anything by value except for strings. */
	final String keyAsBytes;
	final long time;
	final PersistentCooldownQueue parent;
	
	PersistentCooldownQueueItem(SendableGet client, Key key, long time, PersistentCooldownQueue parent) {
		this.client = client;
		this.key = key;
		this.keyAsBytes = HexUtil.bytesToHex(key.getFullKey());
		this.time = time;
		this.parent = parent;
	}

	public void delete(ObjectContainer container) {
		// client not our problem.
		// parent not our problem.
		if(key != null)
			key.removeFrom(container);
		else
			Logger.error(this, "No key to delete on "+this+" keyAsBytes="+keyAsBytes);
		container.delete(this);
	}
}
