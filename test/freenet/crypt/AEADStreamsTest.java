package freenet.crypt;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Random;

import junit.framework.TestCase;

import freenet.support.api.Bucket;
import freenet.support.io.ArrayBucket;
import freenet.support.io.BucketTools;

public class AEADStreamsTest extends TestCase {
    
    public void testSuccessfulRoundTrip() throws IOException {
        Random random = new Random(0x96231307);
        for(int i=0;i<10;i++) {
            ArrayBucket input = new ArrayBucket();
            BucketTools.fill(input, random, 65536);
            checkSuccessfulRoundTrip(16, random, input, new ArrayBucket(), new ArrayBucket());
            checkSuccessfulRoundTrip(24, random, input, new ArrayBucket(), new ArrayBucket());
            checkSuccessfulRoundTrip(32, random, input, new ArrayBucket(), new ArrayBucket());
        }
    }
    
    public void testCorruptedRoundTrip() throws IOException {
        Random random = new Random(0x96231307); // Same seed as first test, intentionally.
        for(int i=0;i<10;i++) {
            ArrayBucket input = new ArrayBucket();
            BucketTools.fill(input, random, 65536);
            checkFailedCorruptedRoundTrip(16, random, input, new ArrayBucket(), new ArrayBucket());
            checkFailedCorruptedRoundTrip(24, random, input, new ArrayBucket(), new ArrayBucket());
            checkFailedCorruptedRoundTrip(32, random, input, new ArrayBucket(), new ArrayBucket());
        }
    }
    
    public void testTruncatedReadsWritesRoundTrip() throws IOException {
        Random random = new Random(0x49ee92f5);
        ArrayBucket input = new ArrayBucket();
        BucketTools.fill(input, random, 512*1024);
        checkSuccessfulRoundTripRandomSplits(16, random, input, new ArrayBucket(), new ArrayBucket());
        checkSuccessfulRoundTripRandomSplits(24, random, input, new ArrayBucket(), new ArrayBucket());
        checkSuccessfulRoundTripRandomSplits(32, random, input, new ArrayBucket(), new ArrayBucket());
    }
    
    public void checkSuccessfulRoundTrip(int keysize, Random random, Bucket input, Bucket output, Bucket decoded) throws IOException {
        byte[] key = new byte[keysize];
        random.nextBytes(key);
        OutputStream os = output.getOutputStream();
        AEADOutputStream cos = AEADOutputStream.innerCreateAES(os, key, random);
        BucketTools.copyTo(input, cos, -1);
        cos.close();
        assertTrue(output.size() > input.size());
        InputStream is = output.getInputStream();
        AEADInputStream cis = AEADInputStream.createAES(is, key);
        BucketTools.copyFrom(decoded, cis, -1);
        assertEquals(decoded.size(), input.size());
        assertTrue(BucketTools.equalBuckets(decoded, input));
    }

    public void checkFailedCorruptedRoundTrip(int keysize, Random random, Bucket input, Bucket output, Bucket decoded) throws IOException {
        byte[] key = new byte[keysize];
        random.nextBytes(key);
        OutputStream os = output.getOutputStream();
        CorruptingOutputStream kos = new CorruptingOutputStream(os, 16L, input.size() + 16, 10, random);
        AEADOutputStream cos = AEADOutputStream.innerCreateAES(kos, key, random);
        BucketTools.copyTo(input, cos, -1);
        cos.close();
        assertTrue(output.size() > input.size());
        InputStream is = output.getInputStream();
        AEADInputStream cis = AEADInputStream.createAES(is, key);
        BucketTools.copyFrom(decoded, cis, -1);
        try {
            cis.close();
            fail("Checksum error should have been seen");
        } catch (AEADVerificationFailedException e) {
            // Expected.
        }
        assertEquals(decoded.size(), input.size());
        assertFalse(BucketTools.equalBuckets(decoded, input));
    }

    public void checkSuccessfulRoundTripRandomSplits(int keysize, Random random, Bucket input, Bucket output, Bucket decoded) throws IOException {
        byte[] key = new byte[keysize];
        random.nextBytes(key);
        OutputStream os = output.getOutputStream();
        AEADOutputStream cos = AEADOutputStream.innerCreateAES(os, key, random);
        BucketTools.copyTo(input, new RandomShortWriteOutputStream(cos, random), -1);
        cos.close();
        assertTrue(output.size() > input.size());
        InputStream is = output.getInputStream();
        AEADInputStream cis = AEADInputStream.createAES(is, key);
        BucketTools.copyFrom(decoded, new RandomShortReadInputStream(cis, random), -1);
        assertEquals(decoded.size(), input.size());
        assertTrue(BucketTools.equalBuckets(decoded, input));
    }


}
