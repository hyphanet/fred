package freenet.support;

import freenet.config.Dimension;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Tests parsing of duration value.
 */
public class FieldsDurationTest {

    /**
     * Duration input with and without various d|h|min|s.
     * With correct result in millis
     */
    private static final Map<String, Integer> durations = new HashMap<String, Integer>() {{
        put("2d", 172_800_000);
        put("3h", 10_800_000);
        put("20m", 1_200_000);
        put("56s", 56_000);
        put("1h30m", 5_400_000);
    }};

    @Test
    public void test() {
        durations.forEach((duration, millis) -> {
            Integer parsed = Fields.parseInt(Fields.trimPerSecond(duration), Dimension.DURATION);
            assertEquals(String.format("Input: %s; Intended: %d; Parsed: %d", duration, millis, parsed),
                    millis, parsed);

            String packed = Fields.intToString(millis, Dimension.DURATION);
            assertEquals(String.format("Input: %d; Intended: %s; Packed: %s", millis, duration, packed),
                    duration, packed);
        });
    }
}
