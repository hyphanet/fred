package freenet.support.io;

import java.io.File;
import java.io.IOException;
import java.util.Random;

import freenet.crypt.DummyRandomSource;
import freenet.crypt.RandomSource;
import freenet.support.Executor;
import freenet.support.SerialExecutor;
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
