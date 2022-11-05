package freenet.crypt;

import static org.junit.Assert.*;

import java.io.IOException;

import org.junit.Test;

import freenet.support.io.ArrayBucket;
import freenet.support.io.BucketTools;

public class AEADBucketTest {
    
    @Test
    public void testCopyBucketNotDivisibleBy16() throws IOException {
        checkCopyBucketNotDivisibleBy16(902);
    }

    public void checkCopyBucketNotDivisibleBy16(long length) throws IOException {
        ArrayBucket underlying = new ArrayBucket();
        byte[] key = new byte[16];
        AEADCryptBucket encryptedBucket = new AEADCryptBucket(underlying, key);
        BucketTools.fill(encryptedBucket, length);
        assertEquals(length + AEADCryptBucket.OVERHEAD, underlying.size());
        assertEquals(length, encryptedBucket.size());
        ArrayBucket copyTo = new ArrayBucket();
        BucketTools.copy(encryptedBucket, copyTo);
        assertEquals(length, encryptedBucket.size());
        assertEquals(length, copyTo.size());
        assertTrue(BucketTools.equalBuckets(encryptedBucket, copyTo));
    }
    
}
