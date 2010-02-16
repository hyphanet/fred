/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import junit.framework.TestCase;

public class LineReadingInputStreamTest extends TestCase {
	public static final String BLOCK = "\ntesting1\ntesting2\r\ntesting3\n\n";
	public static final String[] LINES = new String[] {
		"",
		"testing1",
		"testing2",
		"testing3",
		""
	};
	
	public static final String STRESSED_LINE = "\n\u0114\n";
	public static final String NULL_LINE = "a\u0000\u0000\u0000\u0000\n";
	public static final String LENGTH_CHECKING_LINE = "a\u0000a\n";
	public static final int LENGTH_CHECKING_LINE_LF = 3;
	
	public static final int MAX_LENGTH = 128;
	public static final int BUFFER_SIZE = 128;
	
	public void testReadLineWithoutMarking() throws Exception {
		// try utf8
		InputStream is = new ByteArrayInputStream(STRESSED_LINE.getBytes("utf-8"));
		LineReadingInputStream instance = new LineReadingInputStream(is);
		assertEquals("", instance.readLineWithoutMarking(MAX_LENGTH, BUFFER_SIZE, true));
		assertEquals("\u0114", instance.readLineWithoutMarking(MAX_LENGTH, BUFFER_SIZE, true));
		assertNull(instance.readLineWithoutMarking(MAX_LENGTH, BUFFER_SIZE, true));
		
		// try ISO-8859-1
		is = new ByteArrayInputStream(BLOCK.getBytes("ISO-8859-1"));
		instance = new LineReadingInputStream(is);
		for(String expectedLine : LINES) {
			assertEquals(expectedLine, instance.readLineWithoutMarking(MAX_LENGTH, BUFFER_SIZE, false));
		}
		assertNull(instance.readLineWithoutMarking(MAX_LENGTH, BUFFER_SIZE, false));
		
		// is it returning null?
		is = new MockInputStream();
		instance = new LineReadingInputStream(is);
		assertNull(instance.readLineWithoutMarking(0, BUFFER_SIZE, false));
		assertNull(instance.readLineWithoutMarking(0, 0, false));
		
		// is it throwing?
		is = new ByteArrayInputStream(LENGTH_CHECKING_LINE.getBytes());
		instance = new LineReadingInputStream(is);
		try {
			instance.readLineWithoutMarking(LENGTH_CHECKING_LINE_LF - 1, BUFFER_SIZE, true);
			fail();
		} catch (TooLongException e) {}
		
		// Same test shouldn't throw
		is = new ByteArrayInputStream(LENGTH_CHECKING_LINE.getBytes());
		instance = new LineReadingInputStream(is);
		assertEquals(LENGTH_CHECKING_LINE.substring(0, LENGTH_CHECKING_LINE_LF), instance.readLineWithoutMarking(LENGTH_CHECKING_LINE_LF, BUFFER_SIZE, true));
		
		// is it handling nulls properly? @see #2501
		is = new ByteArrayInputStream(NULL_LINE.getBytes());
		instance = new LineReadingInputStream(is);
		assertEquals(NULL_LINE.substring(0, 5), instance.readLineWithoutMarking(BUFFER_SIZE, 1, true));
	}
	
	public void testReadLine() throws Exception {
		// try utf8
		InputStream is = new ByteArrayInputStream(STRESSED_LINE.getBytes("utf-8"));
		LineReadingInputStream instance = new LineReadingInputStream(is);
		assertEquals("", instance.readLine(MAX_LENGTH, BUFFER_SIZE, true));
		assertEquals("\u0114", instance.readLine(MAX_LENGTH, BUFFER_SIZE, true));
		assertNull(instance.readLine(MAX_LENGTH, BUFFER_SIZE, true));
		
		// try ISO-8859-1
		is = new ByteArrayInputStream(BLOCK.getBytes("ISO-8859-1"));
		instance = new LineReadingInputStream(is);
		for(String expectedLine : LINES) {
			assertEquals(expectedLine, instance.readLine(MAX_LENGTH, BUFFER_SIZE, false));
		}
		assertNull(instance.readLine(MAX_LENGTH, BUFFER_SIZE, false));
		
		// is it returning null? and blocking when it should be?
		is = new MockInputStream();
		instance = new LineReadingInputStream(is);
		assertNull(instance.readLine(0, BUFFER_SIZE, false));
		assertNull(instance.readLine(0, 0, false));
		
		// is it throwing?
		is = new ByteArrayInputStream(LENGTH_CHECKING_LINE.getBytes());
		instance = new LineReadingInputStream(is);
		try {
			instance.readLine(LENGTH_CHECKING_LINE_LF - 1, BUFFER_SIZE, true);
			fail();
		} catch (TooLongException e) {}
		
		// Same test shouldn't throw
		is = new ByteArrayInputStream(LENGTH_CHECKING_LINE.getBytes());
		instance = new LineReadingInputStream(is);
		assertEquals(LENGTH_CHECKING_LINE.substring(0, LENGTH_CHECKING_LINE_LF), instance.readLine(LENGTH_CHECKING_LINE_LF, BUFFER_SIZE, true));
		
		// is it handling nulls properly? @see #2501
		is = new ByteArrayInputStream(NULL_LINE.getBytes());
		instance = new LineReadingInputStream(is);
		assertEquals(NULL_LINE.substring(0, 5), instance.readLine(BUFFER_SIZE, 1, true));
	}

	public void testBothImplementation() throws Exception {
		ByteArrayInputStream bis1 =  new ByteArrayInputStream(BLOCK.getBytes("ISO-8859-1"));
		ByteArrayInputStream bis2 =  new ByteArrayInputStream(BLOCK.getBytes("ISO-8859-1"));
		LineReadingInputStream lris1 = new LineReadingInputStream(bis1);
		LineReadingInputStream lris2 = new LineReadingInputStream(bis2);
		
		while(bis1.available() > 0 || bis2.available() > 0) {
			String stringWithoutMark =lris2.readLineWithoutMarking(MAX_LENGTH*10, BUFFER_SIZE, true);
			String stringWithMark = lris1.readLine(MAX_LENGTH*10, BUFFER_SIZE, true);
			assertEquals(stringWithMark, stringWithoutMark);
		}
		assertNull(lris1.readLine(MAX_LENGTH, BUFFER_SIZE, true));
		assertNull(lris2.readLineWithoutMarking(MAX_LENGTH, BUFFER_SIZE, true));
	}
}
