package freenet.support.math;

/** Average weighted by the number of milliseconds until the next report. */
public class TimeRunningAverage implements RunningAverage, Cloneable {
    
    public TimeRunningAverage clone() {
        try {
            return (TimeRunningAverage) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new Error(e); // Impossible
        }
    }

    /** Time at which the last report was made. 0 if no reports so far. */
    long lastReportTime;
    /** Value of the last report. */
    double lastReportValue;
    /** Start time */
    long firstReportTime;
    /** Total time * reported value from the first report time to the last report time, hence not 
     * including the lastReportValue. */
    double totalTimeTimesValues;
    int reports;
    
    @Override
    public synchronized double currentValue() {
        if(firstReportTime == 0) return Double.NaN; // No reports;
        long now = System.currentTimeMillis();
        double newTotal = totalTimeTimesValues + (now - lastReportTime) * lastReportValue;
        return newTotal / (now - firstReportTime);
    }

    @Override
    public synchronized void report(double d) {
        long now = System.currentTimeMillis();
        if(firstReportTime == 0) {
            reset(now, d);
        } else {
            assert(lastReportTime <= now);
            totalTimeTimesValues += (now - lastReportTime) * lastReportValue;
            lastReportTime = now;
            lastReportValue = d;
        }
        reports++;
    }

    @Override
    public void report(long d) {
        report((double)d);
    }

    @Override
    public double valueIfReported(double r) {
        return currentValue();
    }

    @Override
    public long countReports() {
        return reports;
    }

    public void reset() {
        reset(System.currentTimeMillis(), 0);
    }
    
    public synchronized void reset(long now, double d) {
        // First report.
        lastReportTime = now;
        firstReportTime = now;
        lastReportValue = d;
        totalTimeTimesValues = 0;
        reports = 0;
    }

}
