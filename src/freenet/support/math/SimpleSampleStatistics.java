package freenet.support.math;

public class SimpleSampleStatistics {
    
    final RunningAverage mean;
    final RunningAverage squaredMean;
    
    public SimpleSampleStatistics() {
        mean = new TrivialRunningAverage();
        squaredMean = new TrivialRunningAverage();
    }
    
    public synchronized double mean() {
        return mean.currentValue();
    }
    
    public synchronized double variance() {
        double x = mean.currentValue();
        double x2 = squaredMean.currentValue();
        return x2 - x*x;
    }
    
    public double stddev() {
        return Math.sqrt(variance());
    }
    
    public synchronized long samples() {
        return mean.countReports();
    }
    
    public synchronized void report(double d) {
        mean.report(d);
        squaredMean.report(d*d);
    }
    
    public void report(int i) {
        report((double)i);
    }

    public synchronized long countReports() {
        return mean.countReports();
    }

}
