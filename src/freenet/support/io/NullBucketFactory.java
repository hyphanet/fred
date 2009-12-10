package freenet.support.io;

import java.io.IOException;

import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;

/**
 *
 * @author unknown
 */
public class NullBucketFactory implements BucketFactory {

	public Bucket makeBucket(long size) throws IOException {
		return new NullBucket();
	}

}
