package freenet.support;

import freenet.config.Dimension;
import junit.framework.TestCase;

/**
 * Tests parsing of duration value.
 */
public class FieldsDurationTest extends TestCase {

    /**
     * Duration input with and without various d|h|min|s.
     */
    private static final String[] durations = { "2d", "3h", "20min", "56s", "7890" };

    /**
     * Correct result in millis matched by index with input.
     */
    private static final int[] durationsInMillis = { 172800000, 10800000, 1200000, 56000, 7890 };

    public void test() {
        assert durations.length == durationsInMillis.length;

        int parsed;
        String packed;
        for (int i = 0; i < durations.length; i++) {
            parsed = Fields.parseInt(Fields.trimPerSecond(durations[i]), Dimension.DURATION);
            System.out.format("Input: %s\tParsed: %d\tIntended: %d\n", durations[i], parsed, durationsInMillis[i]);
            assertEquals(parsed, durationsInMillis[i]);

            packed = Fields.intToString(durationsInMillis[i], Dimension.DURATION);
            System.out.format("Input: %d\tPacked: %s\tIntended: %s\n", durationsInMillis[i], packed, durations[i]);
            assertEquals(packed, durations[i]);
        }
    }
}
