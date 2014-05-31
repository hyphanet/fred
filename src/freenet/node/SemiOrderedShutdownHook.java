/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.node;

//~--- JDK imports ------------------------------------------------------------

import java.util.ArrayList;

import static java.util.concurrent.TimeUnit.SECONDS;

public class SemiOrderedShutdownHook extends Thread {
    private static final long TIMEOUT = SECONDS.toMillis(100);
    public static final SemiOrderedShutdownHook singleton = new SemiOrderedShutdownHook();

    static {
        Runtime.getRuntime().addShutdownHook(singleton);
    }

    private final ArrayList<Thread> earlyJobs;
    private final ArrayList<Thread> lateJobs;

    private SemiOrderedShutdownHook() {
        earlyJobs = new ArrayList<Thread>();
        lateJobs = new ArrayList<Thread>();
    }

    public static SemiOrderedShutdownHook get() {
        return singleton;
    }

    public synchronized void addEarlyJob(Thread r) {
        earlyJobs.add(r);
    }

    public synchronized void addLateJob(Thread r) {
        lateJobs.add(r);
    }

    @Override
    public void run() {
        System.err.println("Shutting down...");

        // First run early jobs, all at once, and wait for them to all complete.

        Thread[] early = getEarlyJobs();

        for (Thread r : early) {
            r.start();
        }

        for (Thread r : early) {
            try {
                r.join(TIMEOUT);
            } catch (InterruptedException e) {

                // :(
                // May as well move on
            }
        }

        Thread[] late = getLateJobs();

        // Then run late jobs, all at once, and wait for them to all complete (JVM will exit when we return).
        for (Thread r : late) {
            r.start();
        }

        for (Thread r : late) {
            try {
                r.join(TIMEOUT);
            } catch (InterruptedException e) {

                // :(
                // May as well move on
            }
        }
    }

    private synchronized Thread[] getEarlyJobs() {
        return earlyJobs.toArray(new Thread[earlyJobs.size()]);
    }

    private synchronized Thread[] getLateJobs() {
        return lateJobs.toArray(new Thread[lateJobs.size()]);
    }
}
