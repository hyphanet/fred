package freenet.support.io;

import java.io.IOException;

public class ArrayBucketFactory implements BucketFactory {

	public Bucket makeBucket(long size) throws IOException {
		return new ArrayBucket();
	}

	public void freeBucket(Bucket b) throws IOException {
		// Do nothing
	}

}
