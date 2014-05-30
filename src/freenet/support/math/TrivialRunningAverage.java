/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.support.math;

public final class TrivialRunningAverage implements RunningAverage, Cloneable {
    private static final long serialVersionUID = 1L;
    private long reports;
    private double total;

    public TrivialRunningAverage() {
        reports = 0;
        total = 0.0;
    }

    /**
     *
     * @param average
     */
    public TrivialRunningAverage(TrivialRunningAverage average) {
        this.reports = average.reports;
        this.total = average.total;
    }

    @Override
    public synchronized long countReports() {
        return reports;
    }

    public synchronized double totalValue() {
        return total;
    }

    /**
     *
     * @return
     */
    @Override
    public synchronized double currentValue() {
        return total / reports;
    }

    /**
     *
     * @param d
     */
    @Override
    public synchronized void report(double d) {
        total += d;
        reports++;

        // TODO Auto-generated method stub
    }

    /**
     *
     * @param d
     */
    @Override
    public void report(long d) {
        report((double) d);
    }

    @Override
    public synchronized double valueIfReported(double r) {
        return (total + r) / (reports + 1);
    }

    @Override
    public TrivialRunningAverage clone() {

        // Override clone() for synchronization.
        // Implement Cloneable to shut up findbugs.
        synchronized (this) {
            return new TrivialRunningAverage(this);
        }
    }
}
