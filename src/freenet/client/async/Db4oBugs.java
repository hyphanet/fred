package freenet.client.async;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Query;

/** A collection of bug workarounds for everyone's favourite object database! */
public class Db4oBugs {

	public static<T extends Object> ObjectSet<T> query(ObjectContainer container, Class<T> clazz) {
		// db4o 7.4.84.12673 throws a RuntimeException: Not supported
		// when we use this documented API to query all elements of a class. Isn't that great? :(
//		ObjectSet<HasKeyListener> results =
//			container.query(HasKeyListener.class);
		Query query = container.query();
		query.constrain(HasKeyListener.class);
		return query.execute();
	}

}
