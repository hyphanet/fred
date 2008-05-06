package freenet.support.io;

import freenet.crypt.RandomSource;
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
	final RandomSource strongPRNG;
	final Random weakPRNG;
	final int minSize;
	
	public PaddedEphemerallyEncryptedBucketFactory(BucketFactory factory, RandomSource strongPRNG, Random weakPRNG, int minSize) {
		baseFactory = factory;
		this.minSize = minSize;
		this.strongPRNG = strongPRNG;
		this.weakPRNG = weakPRNG;
	}

	public Bucket makeBucket(long size) throws IOException {
		return new PaddedEphemerallyEncryptedBucket(baseFactory.makeBucket(size), minSize, strongPRNG, weakPRNG);
	}
}
