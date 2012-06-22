package freenet.support;

import junit.framework.TestCase;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Tests writing various types to output streams and reading from input streams.
 */
public class SerializerTest extends TestCase {

	public void test() {
		//Values for testing.
		final Object[] data = new Object[] { true, (byte)9, (short)0xDE, 1234567, 123467890123L, Math.E,
			123.4567f, "testing string", new double[] { Math.PI, 0.1234d},
			new float[] { 2345.678f, 8901.234f }};

		ByteArrayOutputStream byteOutputStream = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(byteOutputStream);

		//Write to stream.
		try {
			for (Object datum : data) Serializer.writeToDataOutputStream(datum, dos);
		} catch (IOException e) {
			throw new IllegalStateException("This test should not throw.", e);
		}

		//Read back.
		DataInputStream dis = new DataInputStream(new ByteArrayInputStream(byteOutputStream.toByteArray()));
		try {
			for (Object datum : data) {
				Object read = Serializer.readFromDataInputStream(datum.getClass(), dis);
				//Might be an array.
				if (read instanceof double[]) {
					assertTrue(Arrays.equals((double[])datum, (double[])read));
				} else if (read instanceof float[]) {
					assertTrue(Arrays.equals((float[])datum, (float[])read));
				} else {
					assertEquals(datum, read);
				}
			}
		} catch (IOException e) {
			throw new IllegalStateException("This test should not throw.", e);
		}
	}
}
