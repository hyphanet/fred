package freenet.support.math;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

/**
 * Time decaying running average.
 * 
 * Decay factor = 0.5 ^ (interval / halflife).
 * 
 * So if the interval is exactly the half-life then reporting 0 will halve the value.
 * 
 * Note that the older version has a half life on the influence of any given report without taking
 * into account the fact that reports persist and accumulate. :) 
 * 
 */
public class TimeDecayingRunningAverage implements RunningAverage {

	private static final long serialVersionUID = -1;
    static final int MAGIC = 0x5ff4ac94;
    
    public final Object clone() {
        return new TimeDecayingRunningAverage(this);
    }
    
	double curValue;
    final double halfLife;
    long lastReportTime;
    long createdTime;
    long totalReports;
    boolean started;
    double defaultValue;
    double minReport;
    double maxReport;
    boolean logDEBUG;
    
    public String toString() {
		long now = System.currentTimeMillis();
		synchronized(this) {
		return super.toString() + ": currentValue="+curValue+", halfLife="+halfLife+
			", lastReportTime="+(now - lastReportTime)+
			"ms ago, createdTime="+(now - createdTime)+
			"ms ago, totalReports="+totalReports+", started="+started+
			", defaultValue="+defaultValue+", min="+minReport+", max="+maxReport;
		}
    }
    
    public TimeDecayingRunningAverage(double defaultValue, long halfLife,
            double min, double max) {
    	curValue = defaultValue;
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
    
    public TimeDecayingRunningAverage(double defaultValue, long halfLife,
            double min, double max, SimpleFieldSet fs) {
    	curValue = defaultValue;
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
        if(fs != null) {
        	started = fs.getBoolean("Started", false);
        	if(started) {
        		curValue = fs.getDouble("CurrentValue", curValue);
        		if(curValue > maxReport || curValue < minReport) {
        			curValue = defaultValue;
        			totalReports = 0;
        			createdTime = System.currentTimeMillis();
        		} else {
        			totalReports = fs.getLong("TotalReports", 0);
            		long uptime = fs.getLong("Uptime", 0);
            		createdTime = System.currentTimeMillis() - uptime;
        		}
        	}
        }
    }
    
    public TimeDecayingRunningAverage(double defaultValue, double halfLife, double min, double max, DataInputStream dis) throws IOException {
        int m = dis.readInt();
        if(m != MAGIC) throw new IOException("Invalid magic "+m);
        int v = dis.readInt();
        if(v != 1) throw new IOException("Invalid version "+v);
        curValue = dis.readDouble();
        if(Double.isInfinite(curValue) || Double.isNaN(curValue))
            throw new IOException("Invalid weightedTotal: "+curValue);
        if((curValue < min) || (curValue > max))
            throw new IOException("Out of range: curValue = "+curValue);
        started = dis.readBoolean();
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
        this.curValue = a.curValue;
    }

    public synchronized double currentValue() {
    	return curValue;
    }

	public void report(double d) {
		long now = System.currentTimeMillis();
		synchronized(this) {
			if(d < minReport) {
				Logger.error(this, "Impossible: "+d+" on "+this, new Exception("error"));
				return;
			}
			if(d > maxReport) {
				Logger.error(this, "Impossible: "+d+" on "+this, new Exception("error"));
				return;
			}
			if(Double.isInfinite(d) || Double.isNaN(d)) {
				Logger.error(this, "Reported infinity or NaN to "+this+" : "+d, new Exception("error"));
				return;
			}
			totalReports++;
			if(!started) {
				curValue = d;
				started = true;
				if(logDEBUG)
					Logger.debug(this, "Reported "+d+" on "+this+" when just started");
			} else if(lastReportTime != -1) { // might be just serialized in
				long thisInterval =
					 now - lastReportTime;
				if(thisInterval < 0) {
					Logger.error(this, "Clock went back in time, ignoring report: "+now+" was "+lastReportTime+" (back "+(-thisInterval)+"ms");
					return;
				}
				double thisHalfLife = halfLife;
				long uptime = now - createdTime;
				if((uptime / 4) < thisHalfLife) thisHalfLife = (uptime / 4);
				if(thisHalfLife == 0) thisHalfLife = 1;
				double changeFactor =
					Math.pow(0.5, (thisInterval) / thisHalfLife);
				double oldCurValue = curValue;
				curValue = curValue * changeFactor /* close to 1.0 if short interval, close to 0.0 if long interval */ 
					+ (1.0 - changeFactor) * d;
				if(logDEBUG)
					Logger.debug(this, "Reported "+d+" on "+this+": thisInterval="+thisInterval+
							", halfLife="+halfLife+", uptime="+uptime+", thisHalfLife="+thisHalfLife+
							", changeFactor="+changeFactor+", oldCurValue="+oldCurValue+
							", currentValue="+currentValue()+
							", thisInterval="+thisInterval+", thisHalfLife="+thisHalfLife+
							", uptime="+uptime+", changeFactor="+changeFactor);
			}
			lastReportTime = now;
		}
	}

    public void report(long d) {
        report((double)d);
    }

    public double valueIfReported(double r) {
        throw new UnsupportedOperationException();
    }

	public void writeDataTo(DataOutputStream out) throws IOException {
		long now = System.currentTimeMillis();
		synchronized(this) {
			out.writeInt(MAGIC);
			out.writeInt(1);
			out.writeDouble(curValue);
			out.writeBoolean(started);
			out.writeLong(totalReports);
			out.writeLong(now - createdTime);
		}
	}

    public int getDataLength() {
        return 4 + 4 + 8 + 8 + 1 + 8 + 8;
    }

    public synchronized long countReports() {
        return totalReports;
    }

	public synchronized long lastReportTime() {
		return lastReportTime;
	}

	public synchronized SimpleFieldSet exportFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet();
		fs.put("Type", "TimeDecayingRunningAverage");
		fs.put("CurrentValue", curValue);
		fs.put("Started", started);
		fs.put("TotalReports", totalReports);
		fs.put("Uptime", System.currentTimeMillis() - createdTime);
		return fs;
	}
}
