package freenet.config;

import org.junit.Test;

import static org.junit.Assert.*;

public class IntOptionTest {

    @Test
    public void twoStringRepresentationsTest() {
        IntOption intOption = new IntOption(null, "test",
                "5m",
                0, false, false, "test", "test", new NullIntCallback(),
                Dimension.DURATION);

        IntOption sameIntOption = new IntOption(null, "test",
                intOption.toString(intOption.currentValue),
                0, false, false, "test", "test", new NullIntCallback(),
                Dimension.DURATION);
        assertEquals(intOption.currentValue, sameIntOption.currentValue);

        sameIntOption = new IntOption(null, "test",
                intOption.toDisplayString(intOption.currentValue),
                0, false, false, "test", "test", new NullIntCallback(),
                Dimension.DURATION);
        assertEquals(intOption.currentValue, sameIntOption.currentValue);
    }
}
