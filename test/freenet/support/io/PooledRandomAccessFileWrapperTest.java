package freenet.support.io;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

public class PooledRandomAccessFileWrapperTest extends BaseRandomAccessThingTest {

    private static final int[] TEST_LIST = new int[] { 0, 1, 32, 64, 32768, 1024*1024, 1024*1024+1 };
    
    public PooledRandomAccessFileWrapperTest() {
        super(TEST_LIST);
    }

    private File base = new File("tmp.pooled-random-access-file-wrapper-test");
    
    public void setUp() {
        base.mkdir();
    }
    
    public void tearDown() {
        FileUtil.removeAll(base);
    }

    @Override
    protected PooledRandomAccessFileWrapper construct(long size) throws IOException {
        File f = File.createTempFile("test", ".tmp", base);
        return new PooledRandomAccessFileWrapper(f, "rw", size);
    }

    /** Simplest test for pooling. TODO Add more. */
    public void testSimplePooling() throws IOException {
        for(int sz : TEST_LIST)
            innerTestSimplePooling(sz);
    }
    
    private void innerTestSimplePooling(int sz) throws IOException {
        PooledRandomAccessFileWrapper.setMaxFDs(1);
        PooledRandomAccessFileWrapper a = construct(sz);
        PooledRandomAccessFileWrapper b = construct(sz);
        byte[] buf1 = new byte[sz];
        byte[] buf2 = new byte[sz];
        Random r = new Random(1153);
        r.nextBytes(buf1);
        r.nextBytes(buf2);
        a.pwrite(0, buf1, 0, buf1.length);
        b.pwrite(0, buf2, 0, buf2.length);
        byte[] cmp1 = new byte[sz];
        byte[] cmp2 = new byte[sz];
        a.pread(0, cmp1, 0, cmp1.length);
        b.pread(0, cmp2, 0, cmp2.length);
        assertTrue(Arrays.equals(cmp1, buf1));
        assertTrue(Arrays.equals(cmp2, buf2));
        a.close();
        b.close();
        a.free();
        b.free();
    }

    // FIXME more tests???
    
}
