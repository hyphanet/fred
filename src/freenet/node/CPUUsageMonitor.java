package freenet.node;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;

import freenet.support.Logger;

/**
 * @author amphibian
 * 
 * Class to determine the current CPU usage.
 */
public class CPUUsageMonitor {

    static class TickStat {

        long user;

        long nice;

        long system;

        long spare;

        boolean reportedFailedProcOpen = false;
        boolean reportedFailedProcParse = false;
        
        boolean read(File f) {
            String firstline;
            FileInputStream fis = null;
            try {
                fis = new FileInputStream(f);
                InputStreamReader ris = new InputStreamReader(fis);
                BufferedReader br = new BufferedReader(ris);
                firstline = br.readLine();
                if(firstline == null) throw new EOFException();
                ris.close();
                br.close();
            } catch (IOException e) {
                if(!reportedFailedProcOpen)
                    Logger.error(this, "Failed to open /proc/stat: "+e, e);
                reportedFailedProcOpen = true;
                if(fis != null) try {
                    fis.close();
                } catch (IOException e1) {
                    Logger.error(this, "Failed to close /proc/stat: "+e, e);
                }
                return false;
            }
            Logger.debug(this, "Read first line: " + firstline);
            if (!firstline.startsWith("cpu")) return false;
            firstline = firstline.substring("cpu".length()).trim();
            String[] split = firstline.split(" ");

            long[] numbers = new long[split.length];
            for(int i=0;i<split.length;i++) {
                try {
                    numbers[i] = Long.parseLong(split[i]);
                } catch (NumberFormatException e) {
                    if(!reportedFailedProcParse)
                        Logger.error(this, "Failed to parse /proc: "+e, e);
                    reportedFailedProcParse = true;
                    return false;
                }
            }
            
            if(split.length == 4) {
                // Linux 2.4/2.2
                user = numbers[0];
                nice = numbers[1];
                system = numbers[2];
                spare = numbers[3];
            } else if(split.length == 8) {
                // Linux 2.6
                // user, nice, system, idle, iowait, irq, softirq, steal
                // No idea what steal is and it's 0 on my box anyway
                user = numbers[0];
                system = numbers[2] + numbers[5] + numbers[6];
                nice = numbers[1];
                spare = numbers[3] + numbers[4];
            } else {
                if(!reportedFailedProcParse)
                    Logger.error(this, "Failed to parse /proc: unrecognized number of elements: "+split.length);
                reportedFailedProcParse = true;
                return false;
            }
            Logger.debug(this, "Read from file: user " + user + " nice " + nice
                    + " system " + system + " spare " + spare);
            return true;
        }

        int calculate(TickStat old) {
            long userdiff = user - old.user;
            long nicediff = nice - old.nice;
            long systemdiff = system - old.system;
            long sparediff = spare - old.spare;

            if (userdiff + nicediff + systemdiff + sparediff <= 0) return 0;
            Logger.debug(this, "User changed by " + userdiff + ", Nice: "
                    + nicediff + ", System: " + systemdiff + ", Spare: "
                    + sparediff);
            int usage = (int) ((100 * (userdiff + nicediff + systemdiff)) / (userdiff
                    + nicediff + systemdiff + sparediff));
            Logger.debug(this, "CPU usage: " + usage);
            return usage;
        }

        void copyFrom(TickStat old) {
            user = old.user;
            nice = old.nice;
            system = old.system;
            spare = old.spare;
        }
    }

    int lastCPULoadEstimate = 0;

    long lastCPULoadEstimateTime = 0;

    File proc = File.separator.equals("/") ? new File("/proc/stat") : null;

    TickStat tsOld = new TickStat();

    TickStat tsNew = null;

    public int getCPUUsage() {
        if(File.separatorChar != '/')
            return -1;
        long now = System.currentTimeMillis();
        if (now - lastCPULoadEstimateTime > 1000) {
            try {
                lastCPULoadEstimateTime = now;
                if (tsNew == null) {
                    tsOld.read(proc);
                    tsNew = new TickStat();
                } else {
                    if (!tsNew.read(proc)) {
                        Logger.minor(this, "Failed to parse /proc");
                        return -1;
                    }
                    lastCPULoadEstimate = tsNew.calculate(tsOld);
                    tsOld.copyFrom(tsNew);
                }
            } catch (Throwable t) {
                lastCPULoadEstimate = -1;
                Logger.normal(this, "Failed real-CPU-load estimation: "
                        + t, t);
            }
        }
        return lastCPULoadEstimate;
    }
}