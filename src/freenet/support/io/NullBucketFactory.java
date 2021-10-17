package freenet.support.io;

import java.io.IOException;

import freenet.support.api.BucketFactory;
import freenet.support.api.RandomAccessBucket;

public class NullBucketFactory implements BucketFactory {

	@Override
	public RandomAccessBucket makeBucket(long size) throws IOException {
		return new NullBucket();
	}

}
