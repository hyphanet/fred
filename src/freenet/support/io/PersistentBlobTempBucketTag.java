package freenet.support.io;

// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
/**
 *
 * @author Toad
 */
public class PersistentBlobTempBucketTag {

	final PersistentBlobTempBucketFactory factory;
	final long index;
	PersistentBlobTempBucket bucket;
	/** db4o bug: http://tracker.db4o.com/browse/COR-1446
	 * We cannot just query for bucket == null, because it will instantiate every
	 * object with bucket != null during the query even though we have an index. */
	boolean isFree;

	PersistentBlobTempBucketTag(PersistentBlobTempBucketFactory f, long idx) {
		factory = f;
		index = idx;
		isFree = true;
	}

}
