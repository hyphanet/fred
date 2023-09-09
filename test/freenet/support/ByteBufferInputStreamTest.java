package freenet.support;

import static org.junit.Assert.*;

import java.io.EOFException;
import java.io.IOException;

import org.junit.Test;

/**
 * @author sdiz
 */
public class ByteBufferInputStreamTest {
	@Test
	public void testUnsignedRead() throws IOException {
		byte[] b = new byte[] { (byte) 0xFF, (byte) 0xFF, (byte) 0 };
		ByteBufferInputStream bis = new ByteBufferInputStream(b);

		assertEquals(0xFF, bis.readUnsignedByte());
		assertEquals(0xFF00, bis.readUnsignedShort());
		
		try {
			bis.readUnsignedByte();
			fail();
		} catch (EOFException e) {
			// expected
		}
	}
}
