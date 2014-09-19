package freenet.support.io;

import static org.junit.Assert.assertArrayEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Random;

import freenet.support.api.RandomAccessBuffer;

public class RandomAccessFileWrapperTest extends RandomAccessBufferTestBase {
    
    private static final int[] TEST_LIST = new int[] { 0, 1, 32, 64, 32768, 1024*1024, 1024*1024+1 };
    
    public RandomAccessFileWrapperTest() {
        super(TEST_LIST);
    }

    private File base = new File("tmp.random-access-file-wrapper-test");
    
    public void setUp() {
        base.mkdir();
    }
    
    public void tearDown() {
        FileUtil.removeAll(base);
    }

    @Override
    protected RandomAccessBuffer construct(long size) throws IOException {
        File f = File.createTempFile("test", ".tmp", base);
        return new FileRandomAccessBuffer(f, size, false);
    }
    
    public void testStoreTo() throws IOException, StorageFormatException, ResumeFailedException {
        File tempFile = File.createTempFile("test-storeto", ".tmp", base);
        byte[] buf = new byte[4096];
        Random r = new Random(1267612);
        r.nextBytes(buf);
        FileRandomAccessBuffer rafw = new FileRandomAccessBuffer(tempFile, buf.length, false);
        rafw.pwrite(0, buf, 0, buf.length);
        byte[] tmp = new byte[buf.length];
        rafw.pread(0, tmp, 0, buf.length);
        assertArrayEquals(buf, tmp);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        rafw.storeTo(dos);
        dos.close();
        rafw.close();
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        FileRandomAccessBuffer restored = (FileRandomAccessBuffer) BucketTools.restoreRAFFrom(dis, null, null, null);
        assertEquals(buf.length, restored.size());
        assertEquals(rafw, restored);
        tmp = new byte[buf.length];
        restored.pread(0, tmp, 0, buf.length);
        assertArrayEquals(buf, tmp);
        restored.close();
        restored.free();
    }

}
