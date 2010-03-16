/*
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package freenet.support;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import junit.framework.TestCase;

/**
 * Test case for {@link freenet.support.Buffer} class.
 * 
 * @author stuart martin &lt;wavey@freenetproject.org&gt;
 */
public class BufferTest extends TestCase {

	private static final String DATA_STRING_1 = "asldkjaskjdsakdhasdhaskjdhaskjhbkasbhdjkasbduiwbxgdoudgboewuydxbybuewyxbuewyuwe" + 
		"dasdkljasndijwnodhnqweoidhnaouidhbnwoduihwnxodiuhnwuioxdhnwqiouhnxwqoiushdnxwqoiudhxnwqoiudhxni";
	
	public void testByteArrayBuffer() {
		
		byte[] data = DATA_STRING_1.getBytes();
		
		Buffer buffer = new Buffer(data);
		
		assertEquals(data, buffer.getData());

		doTestBuffer(data, buffer);
	}

	public void testByteArrayIndexBuffer() {
		
		// get content
		byte[] data = DATA_STRING_1.getBytes();
		
		byte[] dataSub = new byte[5];
		
		// prepare 'substring'
		System.arraycopy(data, 4, dataSub, 0, 5);

		Buffer buffer = new Buffer(data, 4, 5);
		
		assertFalse(Arrays.equals(dataSub,buffer.getData()));

		doTestBuffer(dataSub, buffer);
	}

	public void testBadLength() {
		try {
			new Buffer(new byte[0], 0, -1);
			fail();
		} catch(IllegalArgumentException e) {
			// expect this
		}
		try {
			new Buffer(new byte[0], 0, 1);
			fail();
		} catch(IllegalArgumentException e) {
			// expect this
		}
		try {
			new Buffer(new byte[0], 1, 0);
			fail();
		} catch(IllegalArgumentException e) {
			// expect this
		}
		new Buffer(new byte[1], 1, 0);
		new Buffer(new byte[1], 0, 1);
	}
	
	public void testDataInputStreamBuffer() {
		
		byte[] data = DATA_STRING_1.getBytes();   // get some content
		
		byte[] data2 = new byte[data.length + 4]; // make room for 4 byte length indicator  
		
		int length = DATA_STRING_1.getBytes().length;
		
		// populate length as first 4 bytes
		data2[0] = (byte)((length & 0xff000000) >> 24);
		data2[1] = (byte)((length & 0xff0000)   >> 16);
		data2[2] = (byte)((length & 0xff00)     >>  8);
		data2[3] = (byte)((length & 0xff)            );
		
		System.arraycopy(data, 0, data2, 4, data.length); // populate rest of content
		
		DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data2));
		Buffer buffer = null;
		
		try {
			buffer = new Buffer(dis);
		} catch (IOException e) {
			fail("unexpected exception: " + e.getMessage());
		}
		// perform rest of test with the *original* array because Buffer(DataInputStream) chomps first 4 bytes
		doTestBuffer(data, buffer);
	}
	
	private void doTestBuffer(byte[] data, Buffer buffer) {
		assertEquals(data.length, buffer.getLength());
		
		for(int i = 0; i < buffer.getLength(); i++) {
			assertEquals(data[i], buffer.byteAt(i));
		}
		
		try {
			buffer.byteAt(data.length + 1); // expect exception
			fail();
		} catch(ArrayIndexOutOfBoundsException e) {
			// expect this
		}
	}

	public void testLongBufferToString() {
		
		Buffer buffer = new Buffer(DATA_STRING_1.getBytes());
		String longString = buffer.toString();
		assertEquals("Buffer {" + buffer.getLength() + "}", longString);
	}
	
	public void testEquals() {
		
		Buffer b1 = new Buffer("Buffer1".getBytes());
		Buffer b2 = new Buffer("Buffer2".getBytes());
		Buffer b3 = new Buffer("Buffer1".getBytes());
		
		assertFalse(b1.equals(b2));
		assertTrue(b1.equals(b3));
		assertFalse(b2.equals(b3));
		assertTrue(b1.equals(b1));
		assertTrue(b2.equals(b2));
		assertTrue(b3.equals(b1));				
	}
	
	public void testHashcode() {
		
		Buffer b1 = new Buffer("Buffer1".getBytes());
		Buffer b2 = new Buffer("Buffer2".getBytes());
		Buffer b3 = new Buffer("Buffer1".getBytes());
		
		Map<Buffer, Buffer> hashMap = new HashMap<Buffer, Buffer>();
		
		hashMap.put(b1, b1); 
		hashMap.put(b2, b2);
		hashMap.put(b3, b3); // should clobber b1 due to content

		// see if b3 survived
		Object o = hashMap.get(b3);
		assertFalse(o == b1);
		assertTrue(o == b3);
		
		// see if b1 survived
		o = hashMap.get(b1);
		assertFalse(o == b1);		
		assertTrue(o == b3);
	}
	
	public void testCopy() {
		
		byte[] oldBuf = DATA_STRING_1.getBytes();
		Buffer b = new Buffer(oldBuf);
		
		byte[] newBuf = new byte[b.getLength()];
		b.copyTo(newBuf, 0);
		
		for(int i = 0; i < oldBuf.length; i++) {
			assertEquals(newBuf[i], oldBuf[i]);
		}
	}
}
