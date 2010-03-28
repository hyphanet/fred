package freenet.client.async;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Predicate;

/**
 * The persistent part of a USKManager.
 * @author toad
 *
 */
public class USKManagerPersistent {

	static void init(USKManager manager, ObjectContainer container, final ClientContext context) {
		ObjectSet<USKFetcherTag> set = container.query(new Predicate<USKFetcherTag>() {
			final private static long serialVersionUID = 9150637337019063048L;
			@Override
			public boolean match(USKFetcherTag tag) {
				if(tag.nodeDBHandle != context.nodeDBHandle) return false;
				if(tag.isFinished()) return false;
				return true;
			}
		});
		while(set.hasNext())
			set.next().start(manager, context, container);
	}

}
