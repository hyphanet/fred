package freenet.crypt;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Random;
import java.util.TreeSet;

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
    
    // Introduces one bit errors at chosen points
    class CorruptingOutputStream extends OutputStream {
        
        private final OutputStream os;
        /** Bytes to corrupt, in order */
        private final long[] killBytes;
        private int ptr;
        private long ctr;
        private final Random random;

        public CorruptingOutputStream(OutputStream os, long from, long to, int errors, Random random) {
            this.os = os;
            this.random = random;
            TreeSet<Long> toKill = new TreeSet<Long>();
            for(int i=0;i<errors;i++) {
                long offset = from + nextLong(random, to - from);
                if(!toKill.add(offset)) {
                    i--;
                    continue;
                }
            }
            killBytes = new long[errors];
            Iterator<Long> it = toKill.iterator();
            for(int i=0;i<errors;i++)
                killBytes[i] = it.next();
            ptr = 0;
        }
        
        public void write(int b) throws IOException {
            if(ptr < killBytes.length && ctr++ == killBytes[ptr]) {
                b ^= (1 << random.nextInt(7));
                ptr++;
            }
            os.write(b);
        }
        
        public void close() throws IOException {
            os.close();
        }
        
    }

    public long nextLong(Random random, long range) {
        long maxFair = (Long.MAX_VALUE / range) * range;
        while(true) {
            long r = random.nextLong();
            if(r < 0) r = -r;
            if(r == Long.MIN_VALUE) continue; // Wierd case!
            if(r > maxFair) continue;
            return r % range;
        }
    }

}
