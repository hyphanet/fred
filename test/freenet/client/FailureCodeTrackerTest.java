package freenet.client;

import static org.junit.Assert.*;

import java.io.DataOutputStream;
import java.io.IOException;

import org.junit.Test;

import freenet.support.io.CountedOutputStream;
import freenet.support.io.NullOutputStream;

public class FailureCodeTrackerTest {

    /**
     * Test that the fixed size representation really is fixed size
     */
    @Test
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
        try (DataOutputStream dos = new DataOutputStream(os)) {
            f.writeFixedLengthTo(dos);
        }
        return (int) os.written();
    }

}
