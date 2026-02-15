package freenet.crypt;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import org.junit.Test;

public class MultiHashInputStreamTest {
    private static final byte[] MESSAGE = "Hello, World!".getBytes(StandardCharsets.UTF_8);
    private static final String MESSAGE_MD5 = "65a8e27d8879283831b664bd8b7f0ad4";

    private final ByteArrayInputStream input = new ByteArrayInputStream(MESSAGE);
    private final MultiHashInputStream hash = new MultiHashInputStream(input, HashType.MD5.bitmask);

    @Test
    public void read() throws IOException {
        byte[] buf = new byte[MESSAGE.length];
        buf[0] = (byte) hash.read();
        assertEquals(buf.length - 1, hash.read(buf, 1, buf.length - 1));
        assertEquals(-1, hash.read());
        assertEquals(MESSAGE.length, hash.getReadBytes());
        assertArrayEquals(MESSAGE, buf);

        HashResult[] results = hash.getResults();
        assertEquals(1, results.length);
        assertEquals(HashType.MD5, results[0].type);
        assertEquals(MESSAGE_MD5, results[0].hashAsHex());
    }

    @Test
    public void skip() throws IOException {
        assertEquals(1, hash.skip(1));
        assertEquals(MESSAGE.length - 1, hash.skip(Long.MAX_VALUE));
        assertEquals(-1, hash.read());
        assertEquals(MESSAGE.length, hash.getReadBytes());

        HashResult[] results = hash.getResults();
        assertEquals(1, results.length);
        assertEquals(HashType.MD5, results[0].type);
        assertEquals(MESSAGE_MD5, results[0].hashAsHex());
    }

    @Test
    public void skipZero() throws IOException {
        assertEquals(0, hash.skip(0));
        assertEquals(0, hash.getReadBytes());
    }

    @Test
    public void skipLong() throws IOException {
        byte[] data = new byte[18 * 1024];
        new Random().nextBytes(data);
        byte[] expectedHash = SHA256.digest(data);
        MultiHashInputStream stream = new MultiHashInputStream(new ByteArrayInputStream(data), HashType.SHA256.bitmask);

        assertEquals(17 * 1024, stream.skip(17 * 1024));
        assertEquals(1024, stream.skip(2048));
        assertEquals(data.length, stream.getReadBytes());
        assertArrayEquals(expectedHash, HashResult.get(stream.getResults(), HashType.SHA256));
    }

    @Test
    public void skipAtEnd() throws IOException {
        assertEquals(MESSAGE.length, hash.skip(MESSAGE.length));
        assertEquals(0, hash.skip(1));
    }

    @Test
    public void markResetNotSupported() {
        assertFalse(hash.markSupported());
        assertThrows(IOException.class, hash::reset);
    }
}
