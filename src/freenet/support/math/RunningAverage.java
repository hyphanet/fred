/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.math;

/**
 * A running average. That is, something that takes reports as numbers and generates a current value.
 */
public interface RunningAverage {

    /**
     * Copy the RunningAverage (create a snapshot). Deep copy, the new RA won't change when the first one does.
     */
    RunningAverage clone();

    /**
     * @return
     */
    double currentValue();

    /**
     * @param d
     */
    void report(double d);

    /**
     * @param d
     */
    void report(long d);

    /**
     * Get what currentValue() would be if we reported some given value
     *
     * @param r the value to mimic reporting
     * @return the output of currentValue() if we were to report r
     */
    double valueIfReported(double r);

    /**
     * @return the total number of reports on this RunningAverage so far.
     * Used for weighted averages, confidence/newbieness estimation etc.
     */
    long countReports();
}
