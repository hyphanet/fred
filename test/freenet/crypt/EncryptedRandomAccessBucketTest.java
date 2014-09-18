package freenet.crypt;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.Security;
import java.util.Arrays;
import java.util.Random;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import freenet.support.api.Bucket;
import freenet.support.api.RandomAccessBucket;
import freenet.support.io.ArrayBucket;
import freenet.support.io.BucketTestBase;
import freenet.support.io.BucketTools;
import freenet.support.io.LockableRandomAccessThing;
import freenet.support.io.RAFBucket;
import freenet.support.io.RandomAccessThingTestBase;

public class EncryptedRandomAccessBucketTest extends BucketTestBase {
    
    private final static MasterSecret secret = new MasterSecret();
    private final static EncryptedRandomAccessThingType[] types = 
        EncryptedRandomAccessThingType.values();
    
    static{
        Security.addProvider(new BouncyCastleProvider());
    }
    
    @Override
    protected Bucket makeBucket(long size) throws IOException {
        ArrayBucket underlying = new ArrayBucket();
        return new EncryptedRandomAccessBucket(types[0], underlying, secret);
    }

    @Override
    protected void freeBucket(Bucket bucket) throws IOException {
        bucket.free();
    }
    
    public void testIrregularWrites() throws IOException {
        Random r = new Random(6032405);
        int length = 1024*64+1;
        byte[] data = new byte[length];
        RandomAccessBucket bucket = (RandomAccessBucket) makeBucket(length);
        OutputStream os = bucket.getOutputStream();
        r.nextBytes(data);
        for(int written=0;written<length;) {
            int toWrite = Math.min(length - written, 4095);
            os.write(data, written, toWrite);
            written += toWrite;
        }
        os.close();
        InputStream is = bucket.getInputStream();
        for(int moved=0;moved<length;) {
            int readBytes = Math.min(length - moved, 4095);
            byte[] buf = new byte[readBytes];
            readBytes = is.read(buf);
            assertTrue(readBytes > 0);
            assertTrue(Arrays.equals(Arrays.copyOfRange(buf, 0, readBytes), Arrays.copyOfRange(data, moved, moved+readBytes)));
            moved += readBytes;
        }
        is.close();
        bucket.free();
    }
    
    public void testIrregularWritesNotOverlapping() throws IOException {
        Random r = new Random(6032405);
        int length = 1024*64+1;
        byte[] data = new byte[length];
        RandomAccessBucket bucket = (RandomAccessBucket) makeBucket(length);
        OutputStream os = bucket.getOutputStream();
        r.nextBytes(data);
        for(int written=0;written<length;) {
            int toWrite = Math.min(length - written, 4095);
            os.write(data, written, toWrite);
            written += toWrite;
        }
        os.close();
        InputStream is = bucket.getInputStream();
        for(int moved=0;moved<length;) {
            int readBytes = Math.min(length - moved, 4093); // Co-prime with 4095
            byte[] buf = new byte[readBytes];
            readBytes = is.read(buf);
            assertTrue(readBytes > 0);
            assertTrue(Arrays.equals(Arrays.copyOfRange(buf, 0, readBytes), Arrays.copyOfRange(data, moved, moved+readBytes)));
            moved += readBytes;
        }
        is.close();
        bucket.free();
    }
    
    public void testBucketToRAF() throws IOException {
        Random r = new Random(6032405);
        int length = 1024*64+1;
        byte[] data = new byte[length];
        RandomAccessBucket bucket = (RandomAccessBucket) makeBucket(length);
        OutputStream os = bucket.getOutputStream();
        r.nextBytes(data);
        for(int written=0;written<length;) {
            int toWrite = Math.min(length - written, 4095);
            os.write(data, written, toWrite);
            written += toWrite;
        }
        os.close();
        InputStream is = bucket.getInputStream();
        for(int moved=0;moved<length;) {
            int readBytes = Math.min(length - moved, 4095);
            byte[] buf = new byte[readBytes];
            readBytes = is.read(buf);
            assertTrue(readBytes > 0);
            assertTrue(Arrays.equals(Arrays.copyOfRange(buf, 0, readBytes), Arrays.copyOfRange(data, moved, moved+readBytes)));
            moved += readBytes;
        }
        LockableRandomAccessThing raf = bucket.toRandomAccessThing();
        assertEquals(length, raf.size());
        RAFBucket wrapped = new RAFBucket(raf);
        assertTrue(BucketTools.equalBuckets(bucket, wrapped));
        for(int i=0;i<100;i++) {
            int end = length == 1 ? 1 : r.nextInt(length)+1;
            int start = r.nextInt(end);
            RandomAccessThingTestBase.checkArraySectionEqualsReadData(data, raf, start, end, true);
        }
    }

}
