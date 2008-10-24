package freenet.support.io;

public class PersistentBlobTempBucketTag {
	
	final PersistentBlobTempBucketFactory factory;
	final long index;
	PersistentBlobTempBucket bucket;

	PersistentBlobTempBucketTag(PersistentBlobTempBucketFactory f, long idx) {
		factory = f;
		index = idx;
	}
	
}
