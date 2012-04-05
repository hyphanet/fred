/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.math;

import java.io.Serializable;

public interface RunningAverage extends Serializable {

    /**
     *
     * @return
     */
    public RunningAverage clone();

        /**
         *
         * @return
         */
        public double currentValue();

        /**
         *
         * @param d
         */
        public void report(double d);

        /**
         *
         * @param d
         */
        public void report(long d);

	/**
	 * Get what currentValue() would be if we reported some given value
	 * @param r the value to mimic reporting
	 * @return the output of currentValue() if we were to report r
	 */
	public double valueIfReported(double r);

	/**
	 * @return the total number of reports on this RunningAverage so far.
	 * Used for weighted averages, confidence/newbieness estimation etc.
	 */
	public long countReports();
}
