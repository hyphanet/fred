package freenet.support.io;

import java.io.IOException;

import freenet.support.Bucket;
import freenet.support.BucketFactory;

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
