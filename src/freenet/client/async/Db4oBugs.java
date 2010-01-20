package freenet.client.async;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.ext.Db4oException;
import com.db4o.query.Query;

/** A collection of bug workarounds for everyone's favourite object database! */
public class Db4oBugs {

	public static<T extends Object> ObjectSet<T> query(ObjectContainer container, Class<T> clazz) {
		// db4o 7.4.84.12673 throws a RuntimeException: Not supported
		// when we use this documented API to query all elements of a class. Isn't that great? :(
		// FIXME file a bug with db4o
//		ObjectSet<HasKeyListener> results =
//			container.query(HasKeyListener.class);
		Query query = container.query();
		query.constrain(clazz);
		try {
			return query.execute();
		} catch (NullPointerException e) {
			// Yes this does happen.
			// Databases run on end user systems corrupt themselves randomly due to hardware issues, software issues, and the phase of the moon.
			// But it seems to be our only realistic option. Hopefully we'll have backups soon.
			// A Db4oException will be caught and the database will be killed.
			throw new Db4oException(e);
		}
	}

	/* http://tracker.db4o.com/browse/COR-1436
	 * ArrayList's etc must be stored with:
	 * 
	 * container.ext().store(list, 2)
	 * 
	 * Otherwise everything contained in the arraylist is updated to depth 3, which
	 * is not usually what we want, and can be catastrophic due to storing deactivated
	 * objects and/or using up lots of memory. */
	
	/* http://tracker.db4o.com/browse/COR-1582
	 * Never activate a HashMap to depth 1. This will not only result in its being 
	 * empty, but activating it to depth 2 and thus loading its elements will not be 
	 * possible unless you deactivate it first. Combined with the previous bug this
	 * can cause *really* annoying bugs: maps apparently spontaneously clearing 
	 * themselves, actual cause was it was accidentally activated to depth 1, and then
	 * was accidentally stored by e.g. being moved from one ArrayList to another with
	 * the previous bug. */
	
	/* We are using an oldish version of db4o 7.4 in ext #26 because the newer versions,
	 * e.g. in ext-27pre2, have *really* horrible bugs - all sorts of wierd things 
	 * happen with them, the FEC object duplication bug happens with File's as well, 
	 * there seem to be random object disappearances, there are many many horrible 
	 * things... */
}
