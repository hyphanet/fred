/*
  CPUAdjustingSwapRequestInterval.java / Freenet
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

package freenet.node;

import freenet.support.Logger;

/**
 * @author amphibian
 * 
 * Start at a given default value, adjust up or down according to
 * CPU usage for a given target % usage.
 */
public final class CPUAdjustingSwapRequestInterval implements SwapRequestInterval, Runnable {

    double currentValue;
    int targetCPUUsage;
    CPUUsageMonitor m;
    static final double mulPerSecond = 1.05;
    static final double max = Double.MAX_VALUE / mulPerSecond;
    static final double min = Double.MIN_VALUE;
    
    CPUAdjustingSwapRequestInterval(double initialValue, int targetCPUUsage) {
        currentValue = initialValue;
        this.targetCPUUsage = targetCPUUsage;
        m = new CPUUsageMonitor();
    }

    public void start() {
        Thread t = new Thread(this, "CPUAdjustingSwapRequestInterval");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
    }
    
    public synchronized int getValue() {
        return (int)currentValue;
    }

    public void run() {
        while(true) {
            try {
                long now = System.currentTimeMillis();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                }
                int cpuUsage = m.getCPUUsage();
                long endSleepTime = System.currentTimeMillis();
                double mul = Math.pow(mulPerSecond, ((double)(endSleepTime-now))/1000);
                if(cpuUsage == -1) {
                    Logger.error(this, "Cannot auto-adjust based on CPU usage");
                    return;
                }
                synchronized(this) {
                    if(cpuUsage > targetCPUUsage) {
                        if(currentValue < max)
                            currentValue *= mul; // 5% slower per second
                    } else if(cpuUsage < targetCPUUsage) {
                        if(currentValue > min)
                            currentValue /= mul; // 5% faster per second
                    }
                    if(currentValue < min) currentValue = min;
                    if(currentValue > max) currentValue = max;
                    if(Logger.shouldLog(Logger.MINOR, this))
                    	Logger.minor(this, "CPU usage: "+cpuUsage+" target "+targetCPUUsage+" current value: "+currentValue);
                }
            } catch (Throwable t) {
                Logger.error(this, "Caught "+t+" in "+this, t);
            }
        }
    }
}
