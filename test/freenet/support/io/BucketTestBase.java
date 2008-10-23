/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import junit.framework.TestCase;
import freenet.support.ByteArrayWrapper;
import freenet.support.api.Bucket;

public abstract class BucketTestBase extends TestCase {
	protected byte[] DATA1 = new byte[] { 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 };
	protected byte[] DATA2 = new byte[] { 0x70, (byte) 0x81, (byte) 0x92, (byte) 0xa3, (byte) 0xb4, (byte) 0xc5,
	        (byte) 0xd6, (byte) 0xe7, (byte) 0xf8 };

	protected abstract Bucket makeBucket(long size) throws IOException;

	protected abstract void freeBucket(Bucket bucket) throws IOException;

	public void testReadWrite() throws IOException {
		Bucket bucket = makeBucket(Math.max(DATA1.length, DATA2.length));
		try {
			assertEquals("Size-0", 0, bucket.size());

			// Write
			OutputStream os = bucket.getOutputStream();
			os.write(DATA1);
			os.close();

			assertEquals("Size-1", DATA1.length, bucket.size());

			// Read byte[]
			InputStream is = bucket.getInputStream();
			byte[] data = new byte[DATA1.length];
			int read = 0;
			do {
				read += is.read(data, read, DATA1.length - read);
			} while (read != DATA1.length);
			is.close();

			assertEquals("SimpleRead-1", new ByteArrayWrapper(DATA1), new ByteArrayWrapper(data));

			// Read byte
			is = bucket.getInputStream();
			for (byte b : DATA1)
				assertEquals("SimpleRead-2", b, (byte) is.read());
			is.close();

			// EOF
			assertEquals("SimpleRead-EOF0", -1, is.read(new byte[4]));
			assertEquals("SimpleRead-EOF1", -1, is.read());
			assertEquals("SimpleRead-EOF2", -1, is.read());
		} finally {
			freeBucket(bucket);
		}
	}

	// Write twice -- should overwrite, not append
	public void testReuse() throws IOException {
		Bucket bucket = makeBucket(Math.max(DATA1.length, DATA2.length));
		try {
			// Write
			OutputStream os = bucket.getOutputStream();
			os.write(DATA1);
			os.close();

			// Read byte[]
			InputStream is = bucket.getInputStream();
			byte[] data = new byte[DATA1.length];
			int read = 0;
			do {
				read += is.read(data, read, DATA1.length - read);
			} while (read != DATA1.length);
			is.close();

			assertEquals("Read-1", new ByteArrayWrapper(DATA1), new ByteArrayWrapper(data));

			// Write again 
			os = bucket.getOutputStream();
			os.write(DATA2);
			os.close();

			// Read byte[]
			is = bucket.getInputStream();
			data = new byte[DATA2.length];
			read = 0;
			do {
				read += is.read(data, read, DATA2.length - read);
			} while (read != DATA2.length);
			is.close();

			assertEquals("Read-2", new ByteArrayWrapper(DATA2), new ByteArrayWrapper(data));
		} finally {
			freeBucket(bucket);
		}
	}

	public void testNegative() throws IOException {
		Bucket bucket = makeBucket(Math.max(DATA1.length, DATA2.length));
		try {
			// Write
			OutputStream os = bucket.getOutputStream();
			os.write(0);
			os.write(-1);
			os.write(-2);
			os.write(123);
			os.close();

			// Read byte[]
			InputStream is = bucket.getInputStream();
			assertEquals("Write-0", 0xff & (byte) 0, is.read());
			assertEquals("Write-1", 0xff & (byte) -1, is.read());
			assertEquals("Write-2", 0xff & (byte) -2, is.read());
			assertEquals("Write-123", 0xff & (byte) 123, is.read());
			assertEquals("EOF", -1, is.read());
			is.close();
		} finally {
			freeBucket(bucket);
		}
	}
}
