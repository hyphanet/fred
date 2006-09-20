package freenet.support.io;

import java.io.IOException;


public class PersistentEncryptedTempBucketFactory implements BucketFactory {

	PersistentTempBucketFactory bf;
	
	public PersistentEncryptedTempBucketFactory(PersistentTempBucketFactory bf) {
		this.bf = bf;
	}

	public Bucket makeBucket(long size) throws IOException {
		return bf.makeEncryptedBucket();
	}

	public void freeBucket(Bucket b) throws IOException {
		bf.freeBucket(b);
	}
}
