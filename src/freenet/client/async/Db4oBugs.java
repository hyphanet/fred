package freenet.client.async;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
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
		query.constrain(HasKeyListener.class);
		return query.execute();
	}
	
	/* This one needs to be worked around in the code:
	 * Storing an object containing a HashMap without storing the HashMap first results
	 * in the HashMap being stored empty. After restart, loading it gives an empty
	 * HashMap. This causes all manner of problems! Look at SVN r26092: manifestElements
	 * (a HashMap, in this case a derivative of HashMap for debugging purposes, which
	 * is part of ClientPutDir, and can contain other HashMap's) becomes empty on shutdown,
	 * even though it was full in objectCanNew(), objectOnNew(), and 
	 * objectCanUpdate/objectOnUpdate (which don't get called since changing the store 
	 * depth in FCPClient). To reproduce simply start the node, start an insert for a
	 * small directory, shutdown the node after a few seconds, start it back up. Hook
	 * into e.g. ClientPutDir.receive() (or wait for freeData(), which is where it 
	 * matters). Even when it is activated, it is empty. */

	/* We are using an oldish version of db4o 7.4 in ext #26 because the newer versions,
	 * e.g. in ext-27pre2, have *really* horrible bugs - all sorts of wierd things 
	 * happen with them, the FEC object duplication bug happens with File's as well, 
	 * there seem to be random object disappearances, there are many many horrible 
	 * things... */
}
