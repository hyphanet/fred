package freenet.support;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests parsing of bandwidth limits optionally specified with some indicator of "(bits) per second."
 */
public class FieldTrimSecondTest {

	/**
	 * Bandwidth limit input with and without various "per second" specifiers and SI / IEC units.
	 */
	private static final String[] input = { "50 KiB/s", "1.5 MiB/sec", "128 kbps", "20 KiB", "5800" };

	/**
	 * Correct result in bytes matched by index with input.
	 */
	private static final int[] output = { 50 * 1024, 3 * 1024 * 1024 / 2, (128 / 8) * 1000, 20 * 1024, 5800 };

	@Test
	public void test() {
		assert input.length == output.length;

		int parsed;
		for (int i = 0; i < input.length; i++) {
			parsed = Fields.parseInt(Fields.trimPerSecond(input[i]));
			assertEquals(parsed, output[i]);
		}
	}
}
