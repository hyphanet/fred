/*
  SimpleRunningAverage.java / Freenet
  Copyright (C) amphibian
  Copyright (C) 2005-2006 The Free Network project
  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

package freenet.support.math;

import java.io.DataOutputStream;
import freenet.support.Logger;

/**
 * Simple running average: linear mean of the last N reports.
 * @author amphibian
 */
public class SimpleRunningAverage implements RunningAverage {
	private static final long serialVersionUID = -1;
    final double[] refs;
    int nextSlotPtr=0;
    int curLen=0;
    double total=0;
    int totalReports = 0;
    final double initValue;
    private boolean logDEBUG = Logger.shouldLog(Logger.DEBUG, this);

    public final Object clone() {
        return new SimpleRunningAverage(this);
    }
    
    /**
     * Clear the SRA
     */
    public synchronized void clear() {
        nextSlotPtr = 0;
        curLen = 0;
        totalReports = 0;
        total = 0;
        for(int i=0;i<refs.length;i++) refs[i] = 0.0;
    }
    
    public SimpleRunningAverage(int length, double initValue) {
        refs = new double[length];
        this.initValue = initValue;
        totalReports = 0;
    }
    
    public SimpleRunningAverage(SimpleRunningAverage a) {
        this.curLen = a.curLen;
        this.initValue = a.initValue;
        this.nextSlotPtr = a.nextSlotPtr;
        this.refs = (double[]) a.refs.clone();
        this.total = a.total;
        this.totalReports = a.totalReports;
    }

    public synchronized double currentValue() {
        if(curLen == 0) return initValue;
        return total/curLen;
    }

    public synchronized double valueIfReported(double r) {
        if(curLen < refs.length) {
            return (total+r)/(curLen+1);
        } else {
            // Don't increment curLen because it won't be incremented.
            return (total+r-refs[nextSlotPtr])/curLen;
        }
    }

    public synchronized void report(double d) {
        totalReports++;
		if (logDEBUG)
			Logger.debug(this, "report(" + d + ") on " + this);
		if (curLen < refs.length)
			curLen++;
		else
			total -= popValue();
		pushValue(d);
		total += d;
	}

    protected synchronized void pushValue(double value){
		refs[nextSlotPtr] = value;
		nextSlotPtr++;
		if(nextSlotPtr >= refs.length) nextSlotPtr = 0;
    }

	protected synchronized double popValue(){
		return refs[nextSlotPtr];
	}

    public synchronized String toString() {
        return super.toString() + ": curLen="+curLen+", ptr="+nextSlotPtr+", total="+
        	total+", average="+total/curLen;
    }
    
    public void report(long d) {
        report((double)d);
    }

    public void writeDataTo(DataOutputStream out) {
        throw new UnsupportedOperationException();
    }

    public synchronized long countReports() {
        return totalReports;
    }

    public synchronized double minReportForValue(double targetValue) {
        if(curLen < refs.length) {
            /** Don't need to remove any values before reporting,
             * so is slightly simpler.
             * (total + report) / (curLen + 1) >= targetValue =>
             * report / (curLen + 1) >= targetValue - total/(curLen+1)
             * => report >= (targetValue - total/(curLen + 1)) * (curLen+1)
             * => report >= targetValue * (curLen + 1) - total
             * EXAMPLE:
             * Mean (5, 5, 5, 5, 5, X) = 10 
             * X = 10 * 6 - 25 = 35
             * => Mean = (25 + 35) / 6 = 60/6 = 10
             */
            return targetValue * (curLen + 1) - total;
        } else {
            /** Essentially the same, but:
             * 1) Length will be curLen, not curLen+1, because is full.
             * 2) Take off the value that will be taken off first.
             */
            return targetValue * curLen - (total - refs[nextSlotPtr]);
        }
    }
}
