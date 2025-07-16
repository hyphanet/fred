package freenet.support.math;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;

import freenet.support.SimpleFieldSet;
import org.junit.Test;

public class BootstrappingDecayingRunningAverageTest {
    @Test
    public void decaysOverSubsequentReports() {
        BootstrappingDecayingRunningAverage average = new BootstrappingDecayingRunningAverage(0, 0, 1, 2, null);
        average.report(1);
        assertThat(average.currentValue(), equalTo(1.0));
        average.report(0);
        assertThat(average.currentValue(), equalTo(0.5));
        average.report(0);
        assertThat(average.currentValue(), equalTo(0.25));
        average.report(0);
        assertThat(average.currentValue(), equalTo(0.125));
    }

    @Test
    public void newInstanceHasDefaultValue() {
        BootstrappingDecayingRunningAverage average = new BootstrappingDecayingRunningAverage(1, 0, 1, 2, null);
        assertThat(average.currentValue(), equalTo(1.0));
    }

    @Test
    public void countsNumberOfValidReports() {
        BootstrappingDecayingRunningAverage average = new BootstrappingDecayingRunningAverage(0, 0, 1, 2, null);
        assertThat(average.countReports(), equalTo(0L));

        // Valid report
        average.report(0);
        assertThat(average.countReports(), equalTo(1L));

        // Invalid reports
        average.report(-1000);
        average.report(1000);
        average.report(Double.NEGATIVE_INFINITY);
        average.report(Double.POSITIVE_INFINITY);
        average.report(Double.NaN);
        assertThat(average.countReports(), equalTo(1L));
        assertThat(average.currentValue(), equalTo(0.0));
    }

    @Test
    public void writesStateToSimpleFieldSet() {
        BootstrappingDecayingRunningAverage average = new BootstrappingDecayingRunningAverage(0, 0, 1, 2, null);
        average.report(0.5);

        SimpleFieldSet sfs = average.exportFieldSet(true);
        assertThat(sfs.directKeyValues(), allOf(
                hasEntry("Type", "BootstrappingDecayingRunningAverage"),
                hasEntry("Reports", "1"),
                hasEntry("CurrentValue", "0.5")
        ));
    }

    @Test
    public void canBeRestoredFromSimpleFieldSet() {
        SimpleFieldSet sfs = new SimpleFieldSet(true);
        sfs.putSingle("Type", "BootstrappingDecayingRunningAverage");
        sfs.putSingle("Reports", "1");
        sfs.putSingle("CurrentValue", "0.5");

        BootstrappingDecayingRunningAverage average = new BootstrappingDecayingRunningAverage(0, 0, 1, 2, sfs);
        assertThat(average.currentValue(), equalTo(0.5));
        assertThat(average.countReports(), equalTo(1L));

        average.report(0);
        assertThat(average.currentValue(), equalTo(0.25));
        assertThat(average.countReports(), equalTo(2L));
    }

    @Test
    public void cloneCreatesIndependentInstance() {
        BootstrappingDecayingRunningAverage first = new BootstrappingDecayingRunningAverage(0, 0, 1, 2, null);
        BootstrappingDecayingRunningAverage second = first.clone();
        second.report(0);
        second.report(1);

        // Cloned instance should remain untouched
        assertThat(first.currentValue(), equalTo(0.0));
        assertThat(first.countReports(), equalTo(0L));

        // New instance should be updated
        assertThat(second.currentValue(), equalTo(0.5));
        assertThat(second.countReports(), equalTo(2L));
    }
}
