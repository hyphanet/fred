/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import java.io.IOException;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Predicate;
import com.db4o.query.Query;

import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;


public class PersistentEncryptedTempBucketFactory implements BucketFactory {

	PersistentTempBucketFactory bf;
	
	public PersistentEncryptedTempBucketFactory(PersistentTempBucketFactory bf) {
		this.bf = bf;
	}

	public Bucket makeBucket(long size) throws IOException {
		return bf.makeEncryptedBucket();
	}

	public static PersistentEncryptedTempBucketFactory load(final PersistentTempBucketFactory persistentTempBucketFactory, ObjectContainer container) {
		// This causes an OOM in init. WTF?
//		ObjectSet results = container.query(new Predicate() {
//			public boolean match(PersistentEncryptedTempBucketFactory bf) {
//				return bf.bf == persistentTempBucketFactory;
//			}
//		});
		Query query = container.query();
		query.constrain(PersistentEncryptedTempBucketFactory.class);
		//query.descend("bf").constrain(persistentTempBucketFactory);
		ObjectSet results = query.execute();
		while(results.hasNext()) {
			PersistentEncryptedTempBucketFactory factory = (PersistentEncryptedTempBucketFactory) results.next();
			if(factory.bf == persistentTempBucketFactory) return factory;
			System.err.println("Not matched factory");
		}
		System.err.println("Creating new factory");
		return new PersistentEncryptedTempBucketFactory(persistentTempBucketFactory);
	}
}
