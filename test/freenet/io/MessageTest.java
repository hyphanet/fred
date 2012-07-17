package freenet.io;

import freenet.io.comm.DMT;
import freenet.io.comm.Message;
import freenet.io.comm.MessageType;
import junit.framework.TestCase;

import java.util.Arrays;

/**
 * Tests Message abilities.
 */
public class MessageTest extends TestCase {

	private static final String BOOLEAN = "boolean";
	private static final String BYTE = "byte";
	private static final String SHORT = "short";
	private static final String INT = "int";
	private static final String LONG = "long";
	private static final String DOUBLE = "double";
	private static final String FLOAT = "float";
	private static final String DOUBLE_ARRAY = "double[]";
	private static final String FLOAT_ARRAY = "float[]";

	private static final MessageType test = new MessageType("test", DMT.PRIORITY_LOW) {{
		addField(BOOLEAN, Boolean.class);
		addField(BYTE, Byte.class);
		addField(SHORT, Short.class);
		addField(INT, Integer.class);
		addField(LONG, Long.class);
		addField(DOUBLE, Double.class);
		addField(FLOAT, Float.class);
		addField(DOUBLE_ARRAY, double[].class);
		addField(FLOAT_ARRAY, float[].class);
	}};

	/**
	 * Test that different types can be set and retrieved to and from a Message.
	 */
	public void test() {
		Message msg = new Message(test);

		//Values used for testing.
		final boolean booleanVal = true;
		final byte byteVal = (byte)123;
		final short shortVal = (short)456;
		final int intVal = 78912;
		final long longVal = 3456789123L;
		final double doubleVal = Math.PI;
		final float floatVal = 0.12345f;
		final double[] doubleArrayVal = new double[] { Math.PI, Math.E };
		final float[] floatArrayVal = new float[] { 1234.5678f, 912345.6789f };

		//Set fields.
		msg.set(BOOLEAN, booleanVal);
		msg.set(BYTE, byteVal);
		msg.set(SHORT, shortVal);
		msg.set(INT, intVal);
		msg.set(LONG, longVal);
		msg.set(DOUBLE, doubleVal);
		msg.set(FLOAT, floatVal);
		msg.set(DOUBLE_ARRAY, doubleArrayVal);
		msg.set(FLOAT_ARRAY, floatArrayVal);

		//Read fields.
		assertEquals(booleanVal, msg.getBoolean(BOOLEAN));
		assertEquals(byteVal, msg.getByte(BYTE));
		assertEquals(shortVal, msg.getShort(SHORT));
		assertEquals(intVal, msg.getInt(INT));
		assertEquals(longVal, msg.getLong(LONG));
		assertEquals(doubleVal, msg.getDouble(DOUBLE));
		assertEquals(floatVal, msg.getFloat(FLOAT));
		assertTrue(Arrays.equals(doubleArrayVal, msg.getDoubleArray(DOUBLE_ARRAY)));
		assertTrue(Arrays.equals(floatArrayVal, msg.getFloatArray(FLOAT_ARRAY)));
	}
}
