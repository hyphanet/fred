package freenet.support;

import java.io.IOException;


public interface BucketFactory {
    public Bucket makeBucket(long size) throws IOException;
    public void freeBucket(Bucket b) throws IOException;
}

