package freenet.support.math;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import freenet.support.Logger;


/**
 * @author amphibian
 *
 * For the first N reports, this is equivalent to a simple running
 * average. After that it is a decaying running average with a
 * decayfactor of 1/N. We accomplish this by having decayFactor =
 * 1/(Math.min(#reports + 1, N)). We can therefore:
 * a) Specify N more easily than an arbitrary decay factor.
 * b) We don't get big problems with influence of the initial value,
 * which is usually not very reliable.
 */
public class BootstrappingDecayingRunningAverage implements
        RunningAverage {
	
	private static final long serialVersionUID = -1;
    public final Object clone() {
        return new BootstrappingDecayingRunningAverage(this);
    }
    
    final double min;
    final double max;
    double currentValue;
    long reports;
    final int maxReports;
    // FIXME: debugging!
    long zeros;
    long ones;
    
    public String toString() {
        return super.toString() + ": min="+min+", max="+max+", currentValue="+
        	currentValue+", reports="+reports+", maxReports="+maxReports
        	// FIXME
        	+", zeros: "+zeros+", ones: "+ones
        	;
    }
    
    public BootstrappingDecayingRunningAverage(double defaultValue, double min,
            double max, int maxReports) {
        this.min = min;
        this.max = max;
        reports = 0;
        currentValue = defaultValue;
        this.maxReports = maxReports;
    }
    
    public synchronized double currentValue() {
        return currentValue;
    }

    public synchronized void report(double d) {
        if(d < min) {
            Logger.debug(this, "Too low: "+d, new Exception("debug"));
            d = min;
        }
        if(d > max) {
            Logger.debug(this, "Too high: "+d, new Exception("debug"));
            d = max;
        }
        reports++;
        double decayFactor = 1.0 / (Math.min(reports, maxReports));
        currentValue = (d * decayFactor) + 
        	(currentValue * (1-decayFactor));
        if(d < 0.1 && d >= 0.0) zeros++;
        if(d > 0.9 && d <= 1.0) ones++;
    }

    public void report(long d) {
        report((double)d);
    }

    public synchronized double valueIfReported(double d) {
        if(d < min) {
            Logger.error(this, "Too low: "+d, new Exception("debug"));
            d = min;
        }
        if(d > max) {
            Logger.error(this, "Too high: "+d, new Exception("debug"));
            d = max;
        }
        double decayFactor = 1.0 / (Math.min(reports + 1, maxReports));
        return (d * decayFactor) + 
    		(currentValue * (1-decayFactor));
    }

    int SERIAL_MAGIC = 0xdd60ee7f;
    
    // Preferable to call with a buffered stream!
    public synchronized void writeDataTo(DataOutputStream out) throws IOException {
        out.writeInt(SERIAL_MAGIC);
        out.writeInt(1);
        out.writeInt(maxReports);
        out.writeLong(reports);
        out.writeDouble(currentValue);
    }

    BootstrappingDecayingRunningAverage(DataInputStream dis, double min,
            double max, int maxReports) throws IOException {
        this.max = max;
        this.min = min;
        int magic = dis.readInt();
        if(magic != SERIAL_MAGIC)
            throw new IOException("Invalid magic");
        int ver = dis.readInt();
        if(ver != 1)
            throw new IOException("Invalid version "+ver);
        int mrep = dis.readInt();
        this.maxReports = maxReports;
        if(maxReports != mrep)
            Logger.normal(this, "Changed maxReports: now "+maxReports+
                    ", was "+mrep);
        reports = dis.readLong();
        if(reports < 0)
            throw new IOException("Negative reports");
        currentValue = dis.readDouble();
        if(currentValue < min || currentValue > max)
            throw new IOException("Value out of range: "+currentValue);
    }
    
    public BootstrappingDecayingRunningAverage(BootstrappingDecayingRunningAverage a) {
        this.currentValue = a.currentValue;
        this.max = a.max;
        this.maxReports = a.maxReports;
        this.min = a.min;
        this.reports = a.reports;
    }

    public int getDataLength() {
        return 4 + 4 + 4 + 8 + 8;
    }

    public long countReports() {
        return reports;
    }
}
