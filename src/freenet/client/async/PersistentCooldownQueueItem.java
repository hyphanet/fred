/**
 * 
 */
package freenet.client.async;

import freenet.keys.Key;
import freenet.node.SendableGet;
import freenet.support.HexUtil;

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
}