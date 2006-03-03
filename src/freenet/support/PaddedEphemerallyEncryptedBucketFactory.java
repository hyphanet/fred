package freenet.support;

import java.io.IOException;

import freenet.crypt.RandomSource;
import freenet.crypt.UnsupportedCipherException;

/**
 * Factory wrapper for PaddedEphemerallyEncryptedBucket's, which are themselves
 * wrappers.
 */
public class PaddedEphemerallyEncryptedBucketFactory implements BucketFactory {

	final BucketFactory baseFactory;
	final RandomSource random;
	final int minSize;
	
	public PaddedEphemerallyEncryptedBucketFactory(BucketFactory factory, RandomSource r, int minSize) {
		baseFactory = factory;
		this.minSize = minSize;
		this.random = r;
	}

	public Bucket makeBucket(long size) throws IOException {
		return new PaddedEphemerallyEncryptedBucket(baseFactory.makeBucket(size), minSize, random, true);
	}

	public void freeBucket(Bucket b) throws IOException {
		baseFactory.freeBucket(((PaddedEphemerallyEncryptedBucket)b).getUnderlying());
	}

}
