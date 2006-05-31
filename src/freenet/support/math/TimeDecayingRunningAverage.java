package freenet.support.math;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import freenet.support.Logger;

/**
 * Time based running average.
 * Smoother and less memory usage than an intervalled running average.
 * Does not implement serialization.
 * Uses Math.exp() and a divide on every report so slow if FP is slow.
 * @author amphibian
 * Mathematical basis:
 * We want a weighted average of all reports ever:
 * We have reports r1...rN, which were t1...tN millis ago.
 * Weight of r1 is 0.5^(t1/tHalf)
 * So total is:
 * T = 
 * r1*0.5^(t1/tHalf) +
 * r2*0.5^(t2/tHalf) +
 * r3*0.5^(t3/tHalf) +
 * ...
 * Now, if we move forward tDelta, we get:
 * r1*0.5^((t1+tDelta)/tHalf) +
 * r2*0.5^((t2+tDelta)/tHalf) +
 * r3*0.5^((t3+tDelta)/tHalf) +
 * ...
 * =
 * r1*0.5^((t1/tHalf)+(tDelta/tHalf)) +
 * r2*0.5^((t2/tHalf)+(tDelta/tHalf)) +
 * r3*0.5^((t3/tHalf)+(tDelta/tHalf)) +
 * ...
 * =
 * (r1*0.5^(t1/tHalf)) * 0.5 ^ (tDelta/tHalf) + ...
 * = T * 0.5 ^ (tDelta/tHalf)
 *
 * The weighted mean will then be:
 * Tnew = (Tprev * 0.5 ^ (tDelta/tHalf) + r0 * 0.5^0) /
 *       (0.5^(t1/tHalf) + 0.5^(t2/tHalf) + 0.5^(t3/tHalf) + ...)
 *
 * So we need to track:
 * T: T -> T * 0.5 ^ (tDelta/tHalf) + R
 * W (total weight) = 
 *  0.5^(t1/tHalf) + 0.5^(t2/tHalf) + ...
 * Now, go forward by tDelta millis, we get:
 * W = 0.5^((t1+tDelta)/tHalf) + 0.5^((t2+tDelta)/tHalf) + ...
 * = W * 0.5^tDelta/tHalf
 * Adding a new report, we get: W -> W * 0.5^(tDelta/tHalf) + 1
 * 
 * So our equasions are:
 * T -> T * 0.5 ^ (tDelta/tHalf) + R
 * W -> W * 0.5 ^ (tDelta/tHalf) + 1
 * 
 * And the overall weighted average is T / W
 * 
 * Stability?
 * Lets say tDelta = 0 every time, for maximum increase.
 * Then we get: T -> T + R
 * W -> W + 1
 * Thus both numbers will work fine.
 */
public class TimeDecayingRunningAverage implements RunningAverage {

	private static final long serialVersionUID = -1;
    static final int MAGIC = 0x5ff4ac92;
    
    public final Object clone() {
        return new TimeDecayingRunningAverage(this);
    }
    
	double weightedTotal;
	double totalWeights;
    double halfLife;
    long lastReportTime;
    long createdTime;
    long totalReports;
    boolean started;
    double defaultValue;
    double minReport;
    double maxReport;
    boolean logDEBUG;
    
    public String toString() {
        return super.toString() + ": weightedTotal="+weightedTotal+
        	", totalWeights="+totalWeights+", halfLife="+halfLife+
        	", lastReportTime="+(System.currentTimeMillis()-lastReportTime)+
        	"ms ago, createdTime="+(System.currentTimeMillis()-createdTime)+
        	"ms ago, totalReports="+totalReports+", started="+started+
        	", defaultValue="+defaultValue+", min="+minReport+", max="+maxReport;
    }
    
    public TimeDecayingRunningAverage(double defaultValue, long halfLife,
            double min, double max) {
    	weightedTotal = 0.0;
    	totalWeights = 0.0;
        this.defaultValue = defaultValue;
        started = false;
        this.halfLife = halfLife;
        createdTime = lastReportTime = System.currentTimeMillis();
        this.minReport = min;
        this.maxReport = max;
        totalReports = 0;
        logDEBUG = Logger.shouldLog(Logger.DEBUG, this);
        if(logDEBUG)
        	Logger.debug(this, "Created "+this,
        			new Exception("debug"));
    }
    
    public TimeDecayingRunningAverage(double defaultValue, double halfLife, double min, double max, DataInputStream dis) throws IOException {
        int m = dis.readInt();
        if(m != MAGIC) throw new IOException("Invalid magic "+m);
        int v = dis.readInt();
        if(v != 1) throw new IOException("Invalid version "+v);
        weightedTotal = dis.readDouble();
        if(Double.isInfinite(weightedTotal) || Double.isNaN(weightedTotal))
            throw new IOException("Invalid weightedTotal: "+weightedTotal);
        totalWeights = dis.readDouble();
        if(totalWeights < 0.0 || Double.isInfinite(totalWeights) || Double.isNaN(totalWeights))
            throw new IOException("Invalid totalWeights: "+totalWeights);
        double avg = weightedTotal / totalWeights;
        if(avg < min || avg > max)
            throw new IOException("Out of range: weightedTotal = "+weightedTotal+", totalWeights = "+totalWeights);
        started = dis.readBoolean();
        if(!started) {
            totalWeights = 0.0;
            weightedTotal = 0.0;
        }
        long priorExperienceTime = dis.readLong();
        this.halfLife = halfLife;
        this.minReport = min;
        this.maxReport = max;
        this.defaultValue = defaultValue;
        logDEBUG = Logger.shouldLog(Logger.DEBUG, this);
        lastReportTime = -1;
        createdTime = System.currentTimeMillis() - priorExperienceTime;
        totalReports = dis.readLong();
    }

    public TimeDecayingRunningAverage(TimeDecayingRunningAverage a) {
        this.createdTime = a.createdTime;
        this.defaultValue = a.defaultValue;
        this.halfLife = a.halfLife;
        this.lastReportTime = a.lastReportTime;
        this.maxReport = a.maxReport;
        this.minReport = a.minReport;
        this.started = a.started;
        this.totalReports = a.totalReports;
        this.totalWeights = a.totalWeights;
        this.weightedTotal = a.weightedTotal;
    }

    public synchronized double currentValue() {
        if(!started)
            return defaultValue;
        else return weightedTotal / totalWeights;
    }

    public synchronized void report(double d) {
        if(d < minReport) d = minReport;
        if(d > maxReport) d = maxReport;
        totalReports++;
        long now = System.currentTimeMillis(); 
        if(!started) {
        	weightedTotal = d;
        	totalWeights = 1.0;
            started = true;
            if(logDEBUG)
                Logger.debug(this, "Reported "+d+" on "+this+" when just started");
        } else if(lastReportTime != -1) { // might be just serialized in
            long thisInterval =
                 now - lastReportTime;
            double thisHalfLife = halfLife;
            long uptime = now - createdTime;
            if((uptime / 4) < thisHalfLife) thisHalfLife = (uptime / 4);
            if(thisHalfLife == 0) thisHalfLife = 1;
            double changeFactor =
            	Math.pow(0.5, ((double)thisInterval) / thisHalfLife);
            weightedTotal = weightedTotal * changeFactor + d;
            totalWeights = totalWeights * changeFactor + 1.0;
            if(logDEBUG)
                Logger.debug(this, "Reported "+d+" on "+this+": thisInterval="+thisInterval+
                		", halfLife="+halfLife+", uptime="+uptime+", thisHalfLife="+thisHalfLife+
                        ", changeFactor="+changeFactor+", weightedTotal="+weightedTotal+
						", totalWeights="+totalWeights+", currentValue="+currentValue()+
						", thisInterval="+thisInterval+", thisHalfLife="+thisHalfLife+
						", uptime="+uptime+", changeFactor="+changeFactor);
        }
        lastReportTime = now;
    }

    public void report(long d) {
        report((double)d);
    }

    public double valueIfReported(double r) {
        throw new UnsupportedOperationException();
    }

    public synchronized void writeDataTo(DataOutputStream out) throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(1);
        out.writeDouble(weightedTotal);
        out.writeDouble(totalWeights);
        out.writeBoolean(started);
        out.writeLong(totalReports);
        out.writeLong(System.currentTimeMillis() - createdTime);
    }

    public int getDataLength() {
        return 4 + 4 + 8 + 8 + 1 + 8 + 8;
    }

    public long countReports() {
        return totalReports;
    }
}
