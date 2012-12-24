package freenet.client.async;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.ext.Db4oException;
import com.db4o.query.Query;

/** A collection of bug workarounds for everyone's favourite object database! */
public class Db4oBugs {

	@SuppressWarnings("unchecked")
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
		} catch (IllegalArgumentException e) {
			// This happens too, on corrupted databases. :|
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
	
	// From SimpleManifestPutter:
	// FIXME: DB4O ISSUE: HASHMAP ACTIVATION:
	
	// Unfortunately this class uses a lot of HashMap's, and is persistent.
	// The two things do not play well together!
	
	// Activating a HashMap to depth 1 breaks it badly, so that even if it is then activated to a higher depth, it remains empty.
	// Activating a HashMap to depth 2 loads the elements but does not activate them. In particular, Metadata's used as keys will not have their hashCode loaded so we end up with all of them on the 0th slot.
	// Activating a HashMap to depth 3 loads it properly, including activating both the keys and values to depth 1.
	// Of course, the side effect of activating the values to depth 1 may cause problems ...

	// OPTIONS:
	// 1. Activate to depth 2. Activate the Metadata we are looking for *FIRST*!
	// Then the Metadata we are looking for will be in the correct slot.
	// Everything else will be in the 0'th slot, in one long chain, i.e. if there are lots of entries it will be a very inefficient HashMap.
	
	// 2. Activate to depth 3.
	// If there are lots of entries, we have a significant I/O cost for activating *all* of them.
	// We also have the possibility of a memory/space leak if these are linked from somewhere that assumed they had been deactivated.
	
	// Clearly option 1 is superior. However they both suck.
	// The *correct* solution is to use a HashMap from a primitive type e.g. a String, so we can use depth 2.
	
	// Note that this also applies to HashSet's: The entries are the keys, and they are not activated, so we end up with them all in a long chain off bucket 0, except any that are already active.
	// We don't have any real problems because the caller is generally already active - but it is grossly inefficient.

}
