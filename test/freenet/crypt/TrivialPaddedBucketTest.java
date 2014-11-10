package freenet.crypt;

import java.io.IOException;
import java.io.InputStream;

import freenet.support.io.ArrayBucket;
import freenet.support.io.BucketTools;
import freenet.support.io.FileUtil;
import freenet.support.io.PaddedBucket;
import junit.framework.TestCase;

public class TrivialPaddedBucketTest extends TestCase {
    
    public void testSimple() throws IOException {
        checkSimple(4000, 4096);
        checkSimple(1, 1024);
        checkSimple((1<<17)-1, 1<<17);
    }
    
    public void checkSimple(int length, int expectedLength) throws IOException {
        ArrayBucket input = new ArrayBucket();
        BucketTools.fill(input, length);
        ArrayBucket copy = new ArrayBucket();
        PaddedBucket padded = new PaddedBucket(copy);
        BucketTools.copy(input, padded);
        assertEquals(padded.size(), input.size());
        assertEquals(copy.size(), expectedLength);
        assertTrue(BucketTools.equalBuckets(input, padded));
        InputStream aIn = input.getInputStream();
        InputStream bIn = copy.getInputStream();
        assertTrue(FileUtil.equalStreams(aIn, bIn, length));
    }

}
