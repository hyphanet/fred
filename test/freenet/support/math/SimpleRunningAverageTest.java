package freenet.support.math;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import org.junit.Test;

public class SimpleRunningAverageTest {
    private final SimpleRunningAverage average = new SimpleRunningAverage(4, 100.0);

    @Test
    public void returnsInitialValue() {
        assertThat(average.currentValue(), equalTo(100.0));
    }

    @Test
    public void returnsLinearMeanOfLastReports() {
        average.report(10);
        assertThat(average.currentValue(), equalTo(10.0));
        average.report(40);
        assertThat(average.currentValue(), equalTo(25.0));
        average.report(40);
        assertThat(average.currentValue(), equalTo(30.0));
        average.report(110);
        assertThat(average.currentValue(), equalTo(50.0));

        // Values should start dropping out now
        average.report(40);
        assertThat(average.currentValue(), equalTo(57.5));
        average.report(10);
        assertThat(average.currentValue(), equalTo(50.0));
    }

    @Test
    public void clear() {
        for (int i = 0; i < 4; i++) {
            average.report(12345);
        }
        assertThat(average.currentValue(), equalTo(12345.0));
        assertThat(average.countReports(), equalTo(4L));

        // Clear should reset to initial value = 100
        average.clear();
        assertThat(average.currentValue(), equalTo(100.0));
        assertThat(average.countReports(), equalTo(0L));
    }
}
