package freenet.support.io;

import freenet.support.api.BucketFactory;
import freenet.support.api.RandomAccessBucket;
import java.io.IOException;

public class NullBucketFactory implements BucketFactory {

  @Override
  public RandomAccessBucket makeBucket(long size) throws IOException {
    return new NullBucket();
  }
}
