package freenet.support.io;

import java.io.File;
import java.io.IOException;

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

    // FIXME more tests???
    
}
