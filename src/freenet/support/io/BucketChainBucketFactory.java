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
	final boolean cacheWholeBucket;

	/**
	 * If you want persistent buckets which will be saved every 1000 buckets, and
	 * deleted on restart if not stored by then, then pass in the DBJobRunner.
	 * Otherwise pass in null.
	 * @param bucketFactory The underlying temporary bucket factory.
	 * @param block_size The size of the buckets. Usually 32768.
	 * @param runner The database job runner, to allow us to create 
	 * SegmentedBucketChainBucket's, which use the database to allow for very big
	 * files (but are not themselves persistent).
	 * @param cacheWholeBucket If true, the buckets will cache an entire block_size 
	 * bytes in memory in the OutputStream, writing the whole bucket when it is full
	 * or when the file is closed. This should avoid a lot of seeking and rewriting
	 * and improve performance on all storage types, but especially if the underlying
	 * factory is a PersistentBlobTempBucketFactory (as it usually is). This is 
	 * preferable to just wrapping it in a BufferedOutputStream as implementing it
	 * at this level we are aware of where exactly the boundaries are between 
	 * buckets. <b>Note that flush() is not supported.</b>
	 */
	public BucketChainBucketFactory(BucketFactory bucketFactory, int block_size, DBJobRunner runner, int segmentSize, boolean cacheWholeBucket) {
		this.factory = bucketFactory;
		this.blockSize = block_size;
		this.runner = runner;
		this.segmentSize = segmentSize;
		this.cacheWholeBucket = cacheWholeBucket;
	}

	public Bucket makeBucket(long size) throws IOException {
		if(runner == null)
			return new BucketChainBucket(blockSize, factory, cacheWholeBucket);
		else
			return new SegmentedBucketChainBucket(blockSize, factory, runner, segmentSize, cacheWholeBucket);
	}

}
