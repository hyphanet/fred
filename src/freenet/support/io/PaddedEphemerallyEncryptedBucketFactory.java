package freenet.support.io;

import java.io.IOException;

import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import java.util.Random;

/**
 * Factory wrapper for PaddedEphemerallyEncryptedBucket's, which are themselves
 * wrappers.
 */
public class PaddedEphemerallyEncryptedBucketFactory implements BucketFactory {

	final BucketFactory baseFactory;
	final Random random;
	final int minSize;
	
	public PaddedEphemerallyEncryptedBucketFactory(BucketFactory factory, Random r, int minSize) {
		baseFactory = factory;
		this.minSize = minSize;
		this.random = r;
	}

	public Bucket makeBucket(long size) throws IOException {
		return new PaddedEphemerallyEncryptedBucket(baseFactory.makeBucket(size), minSize, random);
	}
}
