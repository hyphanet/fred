/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.support.Ticker;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.lang.management.ThreadInfo;
import java.util.List;
import java.util.Collections;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Map;

/**
 *  @author desyncr
 *
 *  A class to retrieve data to build diagnostic dumps to help in determining
 *  node bottlenecks or misconfiguration.
 *
 *  This class launches various threads at intervals to retrieve information. This information
 *  is available through the public methods.
 *  Some data pointers are obtained from NodeStats object.
 */
public class NodeDiagnostics {
    private final NodeStats nodeStats;
    private final Ticker ticker;
    private final ThreadMonitor threadMonitor;

    /** Interval in ms the ThreadMonitor is launched */
    private static final int MONITOR_INTERVAL = 10000;
    private static final String MONITOR_THREAD_NAME = "NodeDiagnostics: thread monitor";

    /**
     * @param nodeStats Used to retrieve data points.
     * @param ticker Used to queue timed jobs.
     */
    NodeDiagnostics(final NodeStats nodeStats, final Ticker ticker) {
        this.nodeStats      = nodeStats;
        this.ticker         = ticker;
        this.threadMonitor  = new ThreadMonitor(MONITOR_THREAD_NAME, MONITOR_INTERVAL);
    }

    public void start() throws NodeInitException {
        threadMonitor.start();
    }

    /**
     *
     * @return List of threads registered in NodeStats.getThreads()
     */
    public List<NodeThreadInfo> getThreadInfo() {
        return threadMonitor.getThreads();
    }

    /**
     * Runnable thread to retrieve node thread's information and compiling it into
     * an array of NodeThreadInfo objects.
     */
    private class ThreadMonitor implements Runnable {
        private final String name;
        private final int monitorInterval;

        /** Sleep interval to calculate % CPU used by each thread */
        private static final int CPU_SLEEP_INTERVAL = 1000;
        List<NodeThreadInfo> nodeThreadInfos    = Collections.synchronizedList(new ArrayList<>());

        OperatingSystemMXBean operatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean();
        ThreadMXBean threadMxBean               = ManagementFactory.getThreadMXBean();
        RuntimeMXBean runtimeMxBean             = ManagementFactory.getRuntimeMXBean();

        ThreadMonitor(String name, int monitorInterval) {
            this.name = name;
            this.monitorInterval = monitorInterval;
        }

        public void start() {
            scheduleNext(0);
        }

        private void scheduleNext(int interval) {
            ticker.queueTimedJob(
                this,
                name,
                interval,
                false,
                true
            );
        }

        private void scheduleNext() {
           scheduleNext(monitorInterval);
        }

        @Override
        public void run() {
            Map<Long, Long> threadCPU = new HashMap<>();

            for (ThreadInfo info : threadMxBean.dumpAllThreads(false, false)) {
               threadCPU.put(
                    info.getThreadId(),
                    threadMxBean.getThreadCpuTime(
                        info.getThreadId()
                    )
                );
            }

            long initialUptime = runtimeMxBean.getUptime();

            try {
                Thread.sleep(CPU_SLEEP_INTERVAL);
            } catch (InterruptedException ignored) {
            }

            for (ThreadInfo info : threadMxBean.dumpAllThreads(false, false)) {
                Long prev = threadCPU.get(info.getThreadId());
                if (prev == null) {
                    continue;
                }

                threadCPU.put(
                    info.getThreadId(),
                    threadMxBean.getThreadCpuTime(info.getThreadId()) - prev
                );
            }

            long elapsedUptime = runtimeMxBean.getUptime() - initialUptime;
            double totalElapsedUptime = (elapsedUptime * operatingSystemMXBean.getAvailableProcessors()) * 10000F;

            List<NodeThreadInfo> threads = new ArrayList<>();
            for (Thread t : nodeStats.getThreads()) {
                if (t == null) {
                    continue;
                }

                double cpuUsage = threadCPU.getOrDefault(t.getId(), 0L) / totalElapsedUptime;
                NodeThreadInfo nodeThreadInfo = new NodeThreadInfo(
                    t.getId(),
                    t.getName(),
                    t.getPriority(),
                    t.getThreadGroup().getName(),
                    t.getState().toString(),
                    cpuUsage
                );

                threads.add(nodeThreadInfo);
            }
            synchronized (this) {
                nodeThreadInfos = threads;
            }

            scheduleNext();
        }

        /**
         * @return List of Node threads
         */
        public synchronized List<NodeThreadInfo> getThreads() {
            return new ArrayList<>(nodeThreadInfos);
        }
    }

    /**
     * Class to wrap node thread information.
     */
    public class NodeThreadInfo {
        private final long id;
        private final String name;
        private final int prio;
        private final String groupName;
        private final String state;
        private final double cpu_time;

        /**
         * @param id Thread ID
         * @param name Thread name, or <noname>
         * @param prio Thread priority
         * @param groupName Thread's group name
         * @param state Thread current state (TIMED_WAITING, RUNNABLE, etc)
         * @param cpu_time Thread's % of CPU time used
         */
        NodeThreadInfo(long id, String name, int prio, String groupName, String state, double cpu_time) {
            this.id = id;
            this.name = name;
            this.prio = prio;
            this.groupName = groupName;
            this.state = state;
            this.cpu_time = cpu_time;
        }

        public long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public int getPrio() {
            return prio;
        }

        public String getGroupName() {
            return groupName;
        }

        public String getState() {
            return state;
        }

        public double getCpuTime() {
            return cpu_time;
        }
    }
}
