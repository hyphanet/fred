package freenet.support.io;

import java.io.IOException;

import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;

public class NullBucketFactory implements BucketFactory {

	@Override
	public Bucket makeBucket(long size) throws IOException {
		return new NullBucket();
	}

}
