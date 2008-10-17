package freenet.support.io;

public class PersistentBlobTakenSlotTag {
	final long index;
	final PersistentBlobTempBucketFactory factory;
	final PersistentBlobTempBucket bucket;
	
	PersistentBlobTakenSlotTag(long index, PersistentBlobTempBucketFactory factory, PersistentBlobTempBucket bucket) {
		this.index = index;
		this.factory = factory;
		this.bucket = bucket;
	}
}
