package freenet.support.io;

import freenet.support.api.Bucket;

// A Bucket which does not support being stored to the database. E.g. SegmentedBCB.
/**
 *
 * @author unknown
 */
public interface NotPersistentBucket extends Bucket {
	// No methods
}
