/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.math;

import java.util.Arrays;

/**
 * Simple running average: linear mean of the last N reports.
 *
 * @author amphibian
 */
public final class SimpleRunningAverage implements RunningAverage, Cloneable {
    private final double[] refs;
    private final double initValue;

    private double sum;
    private long totalReports;

    public SimpleRunningAverage(int length, double initValue) {
        refs = new double[length];
        this.initValue = initValue;
    }

    public SimpleRunningAverage(SimpleRunningAverage other) {
        synchronized (other.refs) {
            this.refs = other.refs.clone();
            this.initValue = other.initValue;
            this.sum = other.sum;
            this.totalReports = other.totalReports;
        }
    }

    @Override
    public double currentValue() {
        synchronized (refs) {
            if (totalReports == 0) {
                return initValue;
            }
            return sum / Math.min(refs.length, totalReports);
        }
    }

    @Override
    public double valueIfReported(double r) {
        synchronized (refs) {
            int index = (int) (totalReports % refs.length);
            return (sum - refs[index] + r) / Math.min(refs.length, totalReports);
        }
    }

    @Override
    public void report(double d) {
        synchronized (refs) {
            int index = (int) (totalReports % refs.length);
            sum = sum - refs[index] + d;
            refs[index] = d;
            totalReports++;
        }
    }

    @Override
    public String toString() {
        synchronized (refs) {
            return super.toString() +
                    ", total=" + sum +
                    ", average=" + currentValue();
        }
    }

    @Override
    public void report(long d) {
        report((double) d);
    }

    @Override
    public long countReports() {
        synchronized (refs) {
            return totalReports;
        }
    }

    @Override
    public SimpleRunningAverage clone() {
        return new SimpleRunningAverage(this);
    }

    /**
     * Clear the SRA
     */
    public void clear() {
        synchronized (refs) {
            sum = 0;
            totalReports = 0;
            Arrays.fill(refs, 0.0);
        }
    }
}
