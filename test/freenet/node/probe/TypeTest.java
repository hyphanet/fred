package freenet.node.probe;

import junit.framework.TestCase;

import java.util.HashSet;

/**
 * Tests conversion from code and code validity.
 */
public class TypeTest extends TestCase {

	public void testValidCodes() {
		for (Type t : Type.values()) {
			final byte code = t.code;
			if (Type.isValid(code)) {
				try {
					Type type = Type.valueOf(code);
					//Code of enum should match.
					assertEquals(type.code, code);
				} catch (IllegalArgumentException e) {
					//Should not throw - was determined to be valid.
					assertTrue("valueOf() threw when given valid code " + code + ". (" + t.name() + ")", false);
				}
			} else {
				assertTrue("isValid() returned false for valid code " + code +". (" + t.name() + ")", false);
			}
		}
	}

	public void testInvalidCodes() {
		HashSet<Byte> validCodes = new HashSet<Byte>();
		for (Type type : Type.values()) {
			validCodes.add(type.code);
		}

		for (byte code = Byte.MIN_VALUE; code <= Byte.MAX_VALUE; code++) {
			if (validCodes.contains(code)) continue;

			if (!Type.isValid(code)) {
				//Expected.
			} else {
				assertTrue("isValid() returned true for invalid code " + code + ".", false);
			}
			if (code == Byte.MAX_VALUE) return;
		}
	}

}
