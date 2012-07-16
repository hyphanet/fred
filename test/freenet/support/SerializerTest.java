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
		// Values for basic type testing.
		final Object[] data = new Object[] { true, (byte)9, (short)0xDE, 1234567, 123467890123L, Math.E,
			123.4567f, "testing string", new double[] { Math.PI, 0.1234d},
			new float[] { 2345.678f, 8901.234f }};

		readWrite(data);

		// Double array stored with byte size - test edge cases: 0, 128, 255.
		Object[] edgeCases = new Object[3];

		edgeCases[0] = new double[0];
		edgeCases[1] = new double[128];
		edgeCases[2] = new double[255];

		double value = 0.0;
		for (Object edgeCase : edgeCases) {
			double[] array = (double[]) edgeCase;
			for (int i = 0; i < array.length; i++) {
				array[i] = (value += 5);
			}
		}

		readWrite(edgeCases);
	}

	public void testTooLongDoubleArray() {
		ByteArrayOutputStream byteOutputStream = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(byteOutputStream);

		double value = 0;
		double[] tooLong = new double[256];
		for (int i = 0; i < tooLong.length; i++) {
			tooLong[i] += (value += 5);
		}
		try {
			Serializer.writeToDataOutputStream(tooLong, dos);
		} catch (IOException e) {
			throw new IllegalStateException("This test should not throw an IOException.", e);
		} catch (IllegalArgumentException e) {
			//Serializer should throw when array is too long.
			System.out.println("Threw when too long; should be something about how the array is too long to serialize:");
			e.printStackTrace();
		}
	}

	private static void readWrite(Object[] data) {
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
