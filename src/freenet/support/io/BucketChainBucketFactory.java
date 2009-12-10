package freenet.support.io;

import java.io.IOException;

import freenet.client.async.DBJobRunner;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;

public class BucketChainBucketFactory implements BucketFactory {

	final BucketFactory factory;
	final int blockSize;
	final DBJobRunner runner;
	final int segmentSize;

	/**
	 * If you want persistent buckets which will be saved every 1000 buckets, and
	 * deleted on restart if not stored by then, then pass in the DBJobRunner.
	 * Otherwise pass in null.
	 * @param bucketFactory
	 * @param block_size
	 * @param runner
	 */
	public BucketChainBucketFactory(BucketFactory bucketFactory, int block_size, DBJobRunner runner, int segmentSize) {
		this.factory = bucketFactory;
		this.blockSize = block_size;
		this.runner = runner;
		this.segmentSize = segmentSize;
	}

	public Bucket makeBucket(long size) throws IOException {
		if(runner == null) {
			return new BucketChainBucket(blockSize, factory);
		} else {
			return new SegmentedBucketChainBucket(blockSize, factory, runner, segmentSize);
		}
	}

}
