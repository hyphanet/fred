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
	private volatile boolean reallyEncrypt;
	
	public PaddedEphemerallyEncryptedBucketFactory(BucketFactory factory, RandomSource strongPRNG, Random weakPRNG, int minSize, boolean reallyEncrypt) {
		baseFactory = factory;
		this.minSize = minSize;
		this.strongPRNG = strongPRNG;
		this.weakPRNG = weakPRNG;
		this.reallyEncrypt = reallyEncrypt;
	}

	public Bucket makeBucket(long size) throws IOException {
		Bucket realBucket = baseFactory.makeBucket(size);
		if(!reallyEncrypt)
			return realBucket;
		else
			return new PaddedEphemerallyEncryptedBucket(realBucket, minSize, strongPRNG, weakPRNG);
	}
	
	public void setEncryption(boolean value) {
		reallyEncrypt = value;
	}
	
	public boolean isEncrypting() {
		return reallyEncrypt;
	}
}
