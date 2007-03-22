package org.spaceroots.mantissa.random;

/** This class compute basic statistics on a scalar sample.
 * @version $Id: ScalarSampleStatistics.java 1666 2005-12-15 16:37:55Z luc $
 * @author L. Maisonobe
 */
public class ScalarSampleStatistics {

  /** Number of sample points. */
  private int n;

  /** Minimal value in the sample. */
  private double min;

  /** Maximal value in the sample. */
  private double max;

  /** Sum of the sample values. */
  private double sum;

  /** Sum of the squares of the sample values. */
  private double sum2;

  /** Simple constructor.
   * Build a new empty instance
   */
  public ScalarSampleStatistics() {
    n    = 0;
    min  = Double.NaN;
    max  = min;
    sum  = 0;
    sum2 = 0;
  }

  /** Add one point to the instance.
   * @param x value of the sample point
   */
  public void add(double x) {

    if (n++ == 0) {
      min  = x;
      max  = x;
      sum  = x;
      sum2 = x * x;
    } else {

      if (x < min) {
        min = x;
      } else if (x > max) {
        max = x;
      }

      sum  += x;
      sum2 += x * x;

    }

  }

  /** Add all points of an array to the instance.
   * @param points array of points
   */
  public void add(double[] points) {
    for (int i = 0; i < points.length; ++i) {
      add(points[i]);
    }
  }

  /** Add all the points of another sample to the instance.
   * @param s sample to add
   */
  public void add(ScalarSampleStatistics s) {

    if (s.n == 0) {
      // nothing to add
      return;
    }

    if (n == 0) {
      n    = s.n;
      min  = s.min;
      max  = s.max;
      sum  = s.sum;
      sum2 = s.sum2;
    } else {

      n += s.n;

      if (s.min < min) {
        min = s.min;
      } else if (s.max > max) {
        max = s.max;
      }

      sum  += s.sum;
      sum2 += s.sum2;

    }

  }

  /** Get the number of points in the sample.
   * @return number of points in the sample
   */
  public int size() {
    return n;
  }

  /** Get the minimal value in the sample.
   * @return minimal value in the sample
   */
  public double getMin() {
    return min;
  }

  /** Get the maximal value in the sample.
   * @return maximal value in the sample
   */
  public double getMax() {
    return max;
  }

  /** Get the mean value of the sample.
   * @return mean value of the sample
   */
  public double getMean() {
    return (n == 0) ? 0 : (sum / n);
  }

  /** Get the standard deviation of the underlying probability law.
   * This method estimate the standard deviation considering that the
   * data available are only a <em>sample</em> of all possible
   * values. This value is often called the sample standard deviation
   * (as opposed to the population standard deviation).
   * @return standard deviation of the underlying probability law
   */
  public double getStandardDeviation() {
    if (n < 2) {
      return 0;
    }
    return Math.sqrt((n * sum2 - sum * sum) / (n * (n - 1)));
  }

}
