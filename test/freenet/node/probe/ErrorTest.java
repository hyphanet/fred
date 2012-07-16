package freenet.node.probe;

import junit.framework.TestCase;

import java.util.HashSet;

/**
 * Tests conversion from code and code validity.
 */
public class ErrorTest extends TestCase {

	public void testValidCodes() {
		for (Error t : Error.values()) {
			final byte code = t.code;
			if (Type.isValid(code)) {
				try {
					Error error = Error.valueOf(code);
					//Code of enum should match.
					assertEquals(error.code, code);
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
		for (Error error : Error.values()) {
			validCodes.add(error.code);
		}

		for (byte code = Byte.MIN_VALUE; code <= Byte.MAX_VALUE; code++) {
			if (validCodes.contains(code)) continue;

			if (Error.isValid(code)) {
				assertTrue("isValid() returned true for invalid code " + code + ".", false);
			}
			if (code == Byte.MAX_VALUE) return;
		}
	}

}
