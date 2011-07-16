/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import static freenet.test.Asserts.assertArrayEquals;

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

	public void testAugInputRead1() throws IOException {
		int size = augStream.available();
		assertEquals(size, bHeader.length + bString.length);
		byte[] buffer = new byte[size];
		for (int i=0; i<bJoined.length; i++) {
			int data = augStream.read();
			assertEquals(size-i-1, augStream.available());
			assertEquals((char)data, bJoined[i]);
			buffer[i] = (byte)data;
		}
		assertArrayEquals(bJoined, buffer);
	}

	public void testAugInputReadM() throws IOException {
		_testAugInputRead(-bHeader.length); }
	public void testAugInputReadI() throws IOException {
		_testAugInputRead(-1); }
	public void testAugInputRead0() throws IOException {
		_testAugInputRead(0); }
	public void testAugInputReadP() throws IOException {
		_testAugInputRead(1); }
	public void testAugInputReadZ() throws IOException {
		_testAugInputRead(bString.length); }

	public void _testAugInputRead(int m) throws IOException {
		int i = bHeader.length+m;
		int size = augStream.available();
		byte[] buffer = new byte[size];
		augStream.read(buffer, 0, i);
		assertArrayEquals(
		  Arrays.copyOfRange(Arrays.copyOfRange(bJoined, 0, i), 0, size),
		  buffer);
		augStream.read(buffer, i, size-i);
		assertArrayEquals(bJoined, buffer);
	}

	public void testAugInputSkipAndReadM() throws IOException {
		_testAugInputSkipAndRead(-bHeader.length); }
	public void testAugInputSkipAndReadI() throws IOException {
		_testAugInputSkipAndRead(-1); }
	public void testAugInputSkipAndRead0() throws IOException {
		_testAugInputSkipAndRead(0); }
	public void testAugInputSkipAndReadP() throws IOException {
		_testAugInputSkipAndRead(1); }
	public void testAugInputSkipAndReadZ() throws IOException {
		_testAugInputSkipAndRead(bString.length); }

	public void _testAugInputSkipAndRead(int m) throws IOException {
		int i = bHeader.length+m;
		augStream.skip(i);
		int size = augStream.available();
		assertEquals(size, bJoined.length-i);
		byte[] buffer = new byte[size];
		int read = augStream.read(buffer);
		assertEquals(read, size > 0? size: -1);
		assertArrayEquals(
		  Arrays.copyOfRange(bJoined, i, bJoined.length), buffer);
	}

	public void testDimOutputWrite1() throws IOException {
		for (int i=0; i<bJoined.length; i++) {
			assertArrayEquals(origStream.toByteArray(),
			  (i < bHeader.length)? new byte[0]:
			  Arrays.copyOfRange(bString, 0, i-bHeader.length));
			dimStream.write(bJoined[i]);
		}
		assertArrayEquals(bString, origStream.toByteArray());
	}

	public void testDimOutputWriteM() throws IOException {
		_testDimOutputWrite(-bHeader.length); }
	public void testDimOutputWriteI() throws IOException {
		_testDimOutputWrite(-1); }
	public void testDimOutputWrite0() throws IOException {
		_testDimOutputWrite(0); }
	public void testDimOutputWriteP() throws IOException {
		_testDimOutputWrite(1); }
	public void testDimOutputWriteZ() throws IOException {
		_testDimOutputWrite(bString.length); }

	public void _testDimOutputWrite(int m) throws IOException {
		int i = bHeader.length+m;
		dimStream.write(Arrays.copyOfRange(bJoined, 0, i));
		assertArrayEquals(origStream.toByteArray(),
		  (i < bHeader.length)? new byte[0]:
		  Arrays.copyOfRange(bString, 0, i-bHeader.length));
		dimStream.write(Arrays.copyOfRange(bJoined, i, bJoined.length));
		assertArrayEquals(bString, origStream.toByteArray());
	}

	public void testDimOutputThrow0() throws IOException {
		assertArrayEquals(new byte[0], origStream.toByteArray());
		try {
			dimStream.write('!');
			fail("failed to throw IOException");
		} catch (IOException expected) { }
	}

	public void testDimOutputThrow1() throws IOException {
		dimStream.write('T');
		assertArrayEquals(new byte[0], origStream.toByteArray());
		try {
			dimStream.write("!!!".getBytes());
			fail("failed to throw IOException");
		} catch (IOException expected) { }
	}

}
