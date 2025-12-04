package freenet.support.math;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Random;

import freenet.node.TimeSkewDetectorCallback;
import freenet.support.SimpleFieldSet;
import org.junit.Test;

public class TimeDecayingRunningAverageTest {
    private final Clock clock = new Clock();

    @Test
    public void decaysOverTime() {
        TimeDecayingRunningAverage average = new TimeDecayingRunningAverage(0, 1000, 0, 1, null, null, clock::getWallClockMillis, clock::getMonotonicNanos);
        average.report(0);
        assertThat(average.currentValue(), equalTo(0.0));

        // 1 half-live passes, should take 50% old value (0.0) and 50% new value (1.0)
        clock.tick(1000);
        average.report(1);
        assertThat(average.currentValue(), equalTo(0.5));

        // 2 half-lives pass, should take 25% old value (0.5) and 75% new value (1.0)
        clock.tick(2000);
        average.report(1);
        assertThat(average.currentValue(), equalTo(0.875));
    }

    @Test
    public void newInstanceJumpsToFirstReported() {
        TimeDecayingRunningAverage average = new TimeDecayingRunningAverage(0, 1000, 0, 1, null, null, clock::getWallClockMillis, clock::getMonotonicNanos);
        assertThat(average.currentValue(), equalTo(0.0));

        average.report(1);
        assertThat(average.currentValue(), equalTo(1.0));
    }

    @Test
    public void isNotAffectedByClockDrift() {
        TimeDecayingRunningAverage average = new TimeDecayingRunningAverage(0, 1000, 0, 1, null, null, clock::getWallClockMillis, clock::getMonotonicNanos);
        average.report(0);
        assertThat(average.currentValue(), equalTo(0.0));

        // Wall-clock time drifts backwards
        clock.drift(-12345);

        // 1 half-live passes, should take 50% old value (0.0) and 50% new value (1.0)
        clock.tick(1000);
        average.report(1);
        assertThat(average.currentValue(), equalTo(0.5));
    }

    @Test
    public void reportsNegativeClockDrift() {
        TimeSkewDetectorCallback callback = mock(TimeSkewDetectorCallback.class);
        TimeDecayingRunningAverage average = new TimeDecayingRunningAverage(0, 1000, 0, 1, null, callback, clock::getWallClockMillis, clock::getMonotonicNanos);

        // Wall-clock time drifts forward should not be reported
        clock.drift(12345);
        average.report(0);
        verifyNoInteractions(callback);

        // Wall-clock time drifts backwards should be reported
        clock.drift(-12345);
        average.report(0);
        verify(callback).setTimeSkewDetectedUserAlert();
    }

    @Test
    public void countsNumberOfValidReports() {
        TimeDecayingRunningAverage average = new TimeDecayingRunningAverage(0, 1000, 0, 1, null, null, clock::getWallClockMillis, clock::getMonotonicNanos);
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
    }

    @Test
    public void writesStateToSimpleFieldSet() {
        TimeDecayingRunningAverage average = new TimeDecayingRunningAverage(0, 1000, 0, 1, null, null, clock::getWallClockMillis, clock::getMonotonicNanos);
        clock.tick(1000);
        average.report(0.5);

        SimpleFieldSet sfs = average.exportFieldSet(true);
        assertThat(sfs.directKeyValues(), allOf(
                hasEntry("Type", "TimeDecayingRunningAverage"),
                hasEntry("Started", "true"),
                hasEntry("Uptime", "1000"),
                hasEntry("TotalReports", "1"),
                hasEntry("CurrentValue", "0.5")
        ));
    }

    @Test
    public void ignoresFirstValueWhenRestoredInStartedState() {
        SimpleFieldSet sfs = new SimpleFieldSet(true);
        sfs.putSingle("Type", "TimeDecayingRunningAverage");
        sfs.putSingle("Started", "true");
        sfs.putSingle("Uptime", "1000");
        sfs.putSingle("TotalReports", "1");
        sfs.putSingle("CurrentValue", "0.5");

        TimeDecayingRunningAverage average = new TimeDecayingRunningAverage(0, 1000, 0, 1, sfs, null, clock::getWallClockMillis, clock::getMonotonicNanos);
        assertThat(average.currentValue(), equalTo(0.5));
        assertThat(average.countReports(), equalTo(1L));
        assertThat(average.toString(), containsString("createdTime=1000ms ago"));

        // First reported value should be ignored (but the remaining fields keep counting)
        clock.tick(1000);
        average.report(0);
        assertThat(average.currentValue(), equalTo(0.5));
        assertThat(average.countReports(), equalTo(2L));
        assertThat(average.toString(), containsString("createdTime=2000ms ago"));

        // Subsequent values should be handled normally
        clock.tick(1000);
        average.report(0);
        assertThat(average.currentValue(), equalTo(0.25));
        assertThat(average.countReports(), equalTo(3L));
        assertThat(average.toString(), containsString("createdTime=3000ms ago"));
    }

    @Test
    public void cloneCreatesIndependentInstance() {
        TimeDecayingRunningAverage first = new TimeDecayingRunningAverage(0, 1000, 0, 1, null, null, clock::getWallClockMillis, clock::getMonotonicNanos);
        TimeDecayingRunningAverage second = first.clone();
        second.report(0);
        clock.tick(1000);
        second.report(1);

        // Cloned instance should remain untouched
        assertThat(first.currentValue(), equalTo(0.0));
        assertThat(first.countReports(), equalTo(0L));

        // New instance should be updated
        assertThat(second.currentValue(), equalTo(0.5));
        assertThat(second.countReports(), equalTo(2L));
    }

    static class Clock {
        private long wallClockMillis = System.currentTimeMillis();
        private long monotonicMillis = new Random().nextLong();

        void tick(long millis) {
            wallClockMillis += millis;
            monotonicMillis += millis;
        }

        void drift(long millis) {
            wallClockMillis += millis;
        }

        long getWallClockMillis() {
            return wallClockMillis;
        }

        long getMonotonicNanos() {
            return monotonicMillis * 1_000_000;
        }
    }
}
