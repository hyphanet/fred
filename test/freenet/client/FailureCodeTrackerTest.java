package freenet.client;

import java.io.DataOutputStream;
import java.io.IOException;

import freenet.support.io.CountedOutputStream;
import freenet.support.io.NullOutputStream;
import junit.framework.TestCase;

public class FailureCodeTrackerTest extends TestCase {

    /** Test that the fixed size representation really is fixed size */
    public void testSize() throws IOException {
        testSize(false);
        testSize(true);
    }
    
    public void testSize(boolean insert) throws IOException {
        FailureCodeTracker f = new FailureCodeTracker(insert);
        int fixedLength = FailureCodeTracker.getFixedLength(insert);
        assertEquals(fixedLength, getStoredLength(f));
        f.inc(1);
        assertEquals(fixedLength, getStoredLength(f));
        f.inc(2, 2);
        assertEquals(fixedLength, getStoredLength(f));
    }

    private int getStoredLength(FailureCodeTracker f) throws IOException {
        CountedOutputStream os = new CountedOutputStream(new NullOutputStream());
        DataOutputStream dos = new DataOutputStream(os);
        f.writeFixedLengthTo(dos);
        dos.close();
        return (int) os.written();
    }

}
