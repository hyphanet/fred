package freenet.support.io;

import java.io.IOException;

import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;

public class BucketChainBucketFactory implements BucketFactory {
	
	final BucketFactory factory;
	final int blockSize;

	public BucketChainBucketFactory(BucketFactory bucketFactory, int block_size) {
		this.factory = bucketFactory;
		this.blockSize = block_size;
	}

	public Bucket makeBucket(long size) throws IOException {
		return new BucketChainBucket(blockSize, factory);
	}

}