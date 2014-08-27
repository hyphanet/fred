package freenet.support.io;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Random;

import freenet.crypt.DummyRandomSource;
import freenet.crypt.RandomSource;
import freenet.support.Executor;
import freenet.support.SerialExecutor;
import freenet.support.io.TempBucketFactory.TempBucket;
import freenet.support.io.TempBucketFactory.TempLockableRandomAccessThing;

public class TempBucketFactoryRAFTest extends RandomAccessThingTestBase {
    
    public TempBucketFactoryRAFTest() {
        super(TEST_LIST);
    }
    
    private static final int[] TEST_LIST = new int[] { 0, 1, 32, 64, 32768, 1024*1024, 1024*1024+1 };
    private static final int[] TEST_LIST_NOT_MIGRATED = new int[] { 1, 32, 64, 1024, 2048, 4095 };
    
    private RandomSource strongPRNG = new DummyRandomSource(43210);
    private Random weakPRNG = new Random(12340);
    private Executor exec = new SerialExecutor(NativeThread.NORM_PRIORITY);
    private File f = new File("temp-bucket-raf-test");
    private FilenameGenerator fg;
    private TempBucketFactory factory;
    
    @Override
    public void setUp() throws IOException {
        fg = new FilenameGenerator(weakPRNG, true, f, "temp-raf-test-");
        factory = new TempBucketFactory(exec, fg, 4096, 65536, strongPRNG, weakPRNG, false, 1024*1024*2);
        assertEquals(factory.getRamUsed(), 0);
        FileUtil.removeAll(f);
        f.mkdir();
        assertTrue(f.exists() && f.isDirectory());
    }
    
    @Override
    public void tearDown() {
        assertEquals(factory.getRamUsed(), 0);
        // Everything should have been free()'ed.
        assertEquals(f.listFiles().length, 0);
        FileUtil.removeAll(f);
    }
    
    @Override
    protected RandomAccessThing construct(long size) throws IOException {
        return factory.makeRAF(size);
    }
    
    public void testArrayMigration() throws IOException {
        Random r = new Random(21162506);
        for(int size : TEST_LIST_NOT_MIGRATED)
            innerTestArrayMigration(size, r);
    }
    
    /** Create an array, fill it with random numbers, write it sequentially to the 
     * RandomAccessThing, then read randomly and compare. */
    protected void innerTestArrayMigration(int len, Random r) throws IOException {
        if(len == 0) return;
        byte[] buf = new byte[len];
        r.nextBytes(buf);
        RandomAccessThing raf = construct(len);
        TempLockableRandomAccessThing t = (TempLockableRandomAccessThing) raf;
        assertFalse(t.hasMigrated());
        assertEquals(factory.getRamUsed(), len);
        t.migrateToDisk();
        assertTrue(t.hasMigrated());
        assertEquals(factory.getRamUsed(), 0);
        raf.pwrite(0L, buf, 0, buf.length);
        checkArrayInner(buf, raf, len, r);
        raf.close();
        raf.free();
    }
    
    public void testBucketToRAFWhileArray() throws IOException {
        int len = 4095;
        Random r = new Random(21162101);
        TempBucket bucket = (TempBucket) factory.makeBucket(1024);
        byte[] buf = new byte[len];
        r.nextBytes(buf);
        OutputStream os = bucket.getOutputStream();
        os.write(buf.clone());
        os.close();
        assertTrue(bucket.isRAMBucket());
        assertEquals(len, bucket.size());
        TempLockableRandomAccessThing raf = (TempLockableRandomAccessThing) bucket.toRandomAccessThing();
        assertEquals(len, raf.size());
        assertFalse(raf.hasMigrated());
        checkArrayInner(buf, raf, len, r);
        // Now migrate to disk.
        raf.migrateToDisk();
        File f = ((PooledRandomAccessFileWrapper) raf.getUnderlying()).file;
        assertTrue(f.exists());
        assertEquals(len, f.length());
        assertTrue(raf.hasMigrated());
        assertEquals(factory.getRamUsed(), 0);
        checkArrayInner(buf, raf, len, r);
        raf.close();
        raf.free();
        assertFalse(f.exists());
    }

    public void testBucketToRAFWhileFile() throws IOException {
        int len = 4095;
        Random r = new Random(21162101);
        TempBucket bucket = (TempBucket) factory.makeBucket(1024);
        byte[] buf = new byte[len];
        r.nextBytes(buf);
        OutputStream os = bucket.getOutputStream();
        os.write(buf.clone());
        os.close();
        assertTrue(bucket.isRAMBucket());
        assertEquals(len, bucket.size());
        // Migrate to disk
        bucket.migrateToDisk();
        assertFalse(bucket.isRAMBucket());
        File f = ((TempFileBucket) bucket.getUnderlying()).getFile();
        assertTrue(f.exists());
        assertEquals(len, f.length());
        TempLockableRandomAccessThing raf = (TempLockableRandomAccessThing) bucket.toRandomAccessThing();
        assertTrue(f.exists());
        assertEquals(len, f.length());
        assertEquals(len, raf.size());
        checkArrayInner(buf, raf, len, r);
        assertEquals(factory.getRamUsed(), 0);
        checkArrayInner(buf, raf, len, r);
        raf.close();
        raf.free();
        assertFalse(f.exists());
    }

    private void checkArrayInner(byte[] buf, RandomAccessThing raf, int len, Random r) throws IOException {
        for(int i=0;i<100;i++) {
            int end = len == 1 ? 1 : r.nextInt(len)+1;
            int start = r.nextInt(end);
            checkArraySectionEqualsReadData(buf, raf, start, end);
        }
        checkArraySectionEqualsReadData(buf, raf, 0, len);
        if(len > 1)
            checkArraySectionEqualsReadData(buf, raf, 1, len-1);
    }

}
