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
        return x*x - x2;
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

}
