package freenet.node;

import java.io.BufferedReader;
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

        boolean read(File f) {
            String firstline;
            try {
                FileInputStream fis = new FileInputStream(f);
                InputStreamReader ris = new InputStreamReader(fis);
                BufferedReader br = new BufferedReader(ris);
                firstline = br.readLine();
                ris.close();
            } catch (IOException e) {
                return false;
            }
            Logger.debug(this, "Read first line: " + firstline);
            if (!firstline.startsWith("cpu")) return false;
            long[] data = new long[4];
            for (int i = 0; i < 4; i++) {
                firstline = firstline.substring("cpu".length()).trim();
                firstline = firstline + ' ';
                int x = firstline.indexOf(' ');
                if (x == -1) return false;
                String firstbit = firstline.substring(0, x);
                try {
                    data[i] = Long.parseLong(firstbit);
                } catch (NumberFormatException e) {
                    return false;
                }
                firstline = firstline.substring(x);
            }
            user = data[0];
            nice = data[1];
            system = data[2];
            spare = data[3];
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