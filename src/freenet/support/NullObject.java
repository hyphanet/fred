package freenet.support;

/**
 * A null Object. Used where Object would be used, but can't be, because of db4o's inability
 * to store raw Object's. Usually this is used for synchronization in dual-use (persistent or 
 * not) classes.
 * 
 * See http://tracker.db4o.com/browse/COR-1314
 * @author toad
 */
// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS/
public class NullObject {
    
    // Nothing

}
