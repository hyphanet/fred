package freenet.support.io;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

import junit.framework.TestCase;

public class HeaderStreamsTest extends TestCase {

	final public static String strHeader = "TEST";
	final public static String strString = "testing testing 1 2 3";

	final public static byte[] bHeader = strHeader.getBytes();
	final public static byte[] bString = strString.getBytes();
	final public static byte[] bJoined = (strHeader + strString).getBytes();

	InputStream augStream;
	ByteArrayOutputStream origStream;
	OutputStream dimStream;

	protected void setUp() throws Exception {
		super.setUp();
		InputStream testStream = new ByteArrayInputStream(bString);
		augStream = HeaderStreams.augInput(bHeader, testStream);
		origStream = new ByteArrayOutputStream();
		dimStream = HeaderStreams.dimOutput(bHeader, origStream);
	}

	protected void tearDown() throws Exception {
		super.tearDown();
	}

	public void testAugInputRead() throws IOException {
		int size = augStream.available();
		assertEquals(size, bHeader.length + bString.length);
		byte[] buffer = new byte[size];
		int data = augStream.read();
		assertEquals(size-1, augStream.available());
		assertEquals((char)data, bHeader[0]);
		buffer[0] = (byte)data;
		int read = augStream.read(buffer, 1, size-1);
		assertEquals(read, size-1);
		assertTrue(Arrays.equals(bJoined, buffer));
	}

	public void testAugInputSkip() throws IOException {
		augStream.skip(bHeader.length);
		int size = augStream.available();
		assertEquals(size, bString.length);
		byte[] buffer = new byte[size];
		int read = augStream.read(buffer);
		assertEquals(read, size);
		assertTrue(Arrays.equals(bString, buffer));
	}

	public void testDimOutputWrite() throws IOException {
		dimStream.write(bJoined);
		assertTrue(Arrays.equals(bString, origStream.toByteArray()));
	}

	public void testDimOutputThrow() throws IOException {
		dimStream.write('T');
		assertTrue(Arrays.equals(new byte[0], origStream.toByteArray()));
		try {
			dimStream.write("!!!".getBytes());
			fail("failed to throw IOException");
		} catch (IOException e) {
			// expected
		}
	}

}
