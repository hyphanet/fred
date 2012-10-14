package freenet.support;

import junit.framework.TestCase;

/**
 * Tests parsing of bandwidth limits optionally specified with some indicator of "(bits) per second."
 */
public class FieldTrimSecondTest extends TestCase {

	/**
	 * Bandwidth limit input with and without various "per second" specifiers and SI / IEC units.
	 */
	private static final String[] input = { "50 KiB/s", "1.5 MiB/sec", "128 kbps", "20 KiB", "5800" };

	/**
	 * Correct result in bytes matched by index with input.
	 */
	private static final int[] output = { 50 * 1024, 3 * 1024 * 1024 / 2, 128 * 1000, 20 * 1024, 5800 };

	public void test() {
		assert input.length == output.length;

		int parsed;
		for (int i = 0; i < input.length; i++) {
			parsed = Fields.parseInt(Fields.trimPerSecond(input[i]));
			System.out.format("Input: %s\tParsed: %d\tIntended: %d\n", input[i], parsed, output[i]);
			assertEquals(parsed, output[i]);
		}
	}
}
