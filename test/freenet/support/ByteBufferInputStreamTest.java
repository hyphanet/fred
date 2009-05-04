package freenet.support;

import java.io.EOFException;
import java.io.IOException;

import junit.framework.TestCase;

/**
 * @author sdiz
 */
public class ByteBufferInputStreamTest extends TestCase {
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
