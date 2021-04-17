/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.diagnostics.threads;

import freenet.node.*;
import freenet.node.diagnostics.*;
import freenet.support.*;

import java.lang.management.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * Runnable thread to retrieve node thread's information and compiling it into
 * an array of NodeThreadInfo objects.
 */
public class DefaultThreadDiagnostics implements Runnable, ThreadDiagnostics {
    private final String name;
    private final int monitorInterval;

    private final NodeStats nodeStats;
    private final Ticker ticker;

    /** Sleep interval to calculate % CPU used by each thread */
    private static final int MONITOR_INTERVAL = 1000;
    private static final String MONITOR_THREAD_NAME = "NodeDiagnostics: thread monitor";

    private final AtomicReference<List<NodeThreadInfo>> nodeThreadInfo = new AtomicReference<>(new ArrayList<>());

    private final OperatingSystemMXBean operatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean();
    private final ThreadMXBean threadMxBean               = ManagementFactory.getThreadMXBean();
    private final RuntimeMXBean runtimeMxBean             = ManagementFactory.getRuntimeMXBean();

    /**
     * @param nodeStats Used to retrieve data points
     * @param ticker Used to queue timed jobs
     * @param name Thread name
     * @param monitorInterval Sleep intervals to retrieve CPU usage
     */
    public DefaultThreadDiagnostics(NodeStats nodeStats, Ticker ticker, String name, int monitorInterval) {
        this.nodeStats = nodeStats;
        this.ticker = ticker;
        this.name = name;
        this.monitorInterval = monitorInterval;
    }

    /*
     * @param nodeStats Used to retrieve data points
     * @param ticker Used to queue timed jobs
     */
    public DefaultThreadDiagnostics(NodeStats nodeStats, Ticker ticker) {
        this(nodeStats, ticker, MONITOR_THREAD_NAME, MONITOR_INTERVAL);
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

    public void start() {
        scheduleNext(0);
    }

    private void scheduleNext() {
        scheduleNext(monitorInterval);
    }

    /** Map<ThreadId, ThreadCpuTimeNs> */
    private final Map<Long, Long> threadCPU = new HashMap<>();
    private long initialUptime = -1;

    @Override
    public void run() {
        if (initialUptime == -1) {
            initialUptime = runtimeMxBean.getUptime();

            for (ThreadInfo info : threadMxBean.dumpAllThreads(false, false)) {
                threadCPU.put(
                    info.getThreadId(),
                    threadMxBean.getThreadCpuTime(
                        info.getThreadId()
                    )
                );
            }

            scheduleNext();
            return;
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

        nodeThreadInfo.set(buildThreadList());

        initialUptime = -1;
        threadCPU.clear();

        scheduleNext();
    }

    /**
     * @return List of NodeThreadInfo
     */
    private List<NodeThreadInfo> buildThreadList() {
        List<NodeThreadInfo> threads = new ArrayList<>();
        double elapsedUptime = runtimeMxBean.getUptime() - initialUptime;
        double totalElapsedUptime = elapsedUptime * operatingSystemMXBean.getAvailableProcessors();

        for (Thread thread : nodeStats.getThreads()) {
            if (thread == null) {
                continue;
            }

            double cpuUsage = (threadCPU.getOrDefault(thread.getId(), 0L) / 1000000d) / totalElapsedUptime * 100;
            NodeThreadInfo nodeThreadInfo = new NodeThreadInfo(
                thread.getId(),
                thread.getName(),
                thread.getPriority(),
                thread.getThreadGroup().getName(),
                thread.getState().toString(),
                cpuUsage
            );

            threads.add(nodeThreadInfo);
        }

        return threads;
    }

    /**
     * @return List of Node threads
     */
    public List<NodeThreadInfo> getThreads() {
        return nodeThreadInfo.get();
    }
}
