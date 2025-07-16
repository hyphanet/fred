package freenet.crypt;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class MultiHashOutputStreamTest {
    private static final byte[] MESSAGE = "Hello, World!".getBytes(StandardCharsets.UTF_8);
    private static final String MESSAGE_MD5 = "65a8e27d8879283831b664bd8b7f0ad4";

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final MultiHashOutputStream hash = new MultiHashOutputStream(output, HashType.MD5.bitmask);

    @Test
    public void write() throws IOException {
        hash.write(MESSAGE[0]);
        hash.write(MESSAGE, 1, MESSAGE.length - 1);
        assertArrayEquals(MESSAGE, output.toByteArray());

        HashResult[] results = hash.getResults();
        assertEquals(1, results.length);
        assertEquals(HashType.MD5, results[0].type);
        assertEquals(MESSAGE_MD5, results[0].hashAsHex());
    }
}
