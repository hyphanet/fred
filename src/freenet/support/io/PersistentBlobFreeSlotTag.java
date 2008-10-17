package freenet.support.io;

public class PersistentBlobFreeSlotTag {
	final long index;
	final PersistentBlobTempBucketFactory factory;
	
	PersistentBlobFreeSlotTag(long index, PersistentBlobTempBucketFactory factory) {
		this.index = index;
		this.factory = factory;
	}
}
