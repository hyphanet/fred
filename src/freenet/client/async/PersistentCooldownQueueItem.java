/**
 * 
 */
package freenet.client.async;

import freenet.keys.Key;
import freenet.node.SendableGet;

public class PersistentCooldownQueueItem {
	final SendableGet client;
	final Key key;
	final long time;
	final PersistentCooldownQueue parent;
	
	PersistentCooldownQueueItem(SendableGet client, Key key, long time, PersistentCooldownQueue parent) {
		this.client = client;
		this.key = key;
		this.time = time;
		this.parent = parent;
	}
}