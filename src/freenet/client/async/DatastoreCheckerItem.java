package freenet.client.async;

import freenet.node.SendableGet;

/**
 * Persistent tag for a persistent request which needs to check the datastore
 * and then be registered.
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 *
 */
// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
public class DatastoreCheckerItem {
    
    final long nodeDBHandle;
    final SendableGet getter;
    final short prio;
    long chosenBy;
    final BlockSet blocks;
    
    DatastoreCheckerItem(SendableGet getter, long nodeDBHandle, short prio, BlockSet blocks) {
        this.getter = getter;
        this.nodeDBHandle = nodeDBHandle;
        this.prio = prio;
        this.blocks = blocks;
    }

}
