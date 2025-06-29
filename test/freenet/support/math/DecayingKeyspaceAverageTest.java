package freenet.support.math;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class DecayingKeyspaceAverageTest {
    private final DecayingKeyspaceAverage average = new DecayingKeyspaceAverage(0.5, 2, null);

    @Test
    public void wrapsAround() {
        average.report(0.5);
        assertThat(average.currentValue(), equalTo(0.5));
        average.report(1.0);
        assertThat(average.currentValue(), equalTo(0.75));
        average.report(0.25);
        assertThat(average.currentValue(), equalTo(0.0));
        average.report(0.25);
        assertThat(average.currentValue(), equalTo(0.125));
        average.report(0.875);
        assertThat(average.currentValue(), equalTo(0.0));
        average.report(0.75);
        assertThat(average.currentValue(), equalTo(0.875));
    }

    @Test
    public void rejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> average.report(1.1));
        assertThrows(IllegalArgumentException.class, () -> average.report(-0.1));
        assertThrows(IllegalArgumentException.class, () -> average.report(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> average.report(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> average.report(Double.NEGATIVE_INFINITY));
    }
}
