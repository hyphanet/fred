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

    private final AtomicReference<Map<Long, Long>> threadCPU = new AtomicReference<>(new HashMap<>());
    private long lastCycle = -1;

    @Override
    public void run() {
        Map<Long, Long> delta = new HashMap<>(threadCPU.get());
        Map<Long, Long> threads = new HashMap<>();
        for (ThreadInfo info : threadMxBean.dumpAllThreads(false, false)) {
            long threadId = info.getThreadId();
            long cpuTime = threadMxBean.getThreadCpuTime(threadId);

            delta.put(threadId, cpuTime - delta.getOrDefault(threadId, 0L));
            threads.put(threadId, cpuTime);
        }

        threadCPU.set(threads);
        nodeThreadInfo.set(buildThreadList(delta));
        lastCycle = runtimeMxBean.getUptime();

        scheduleNext();
    }

    /**
     * @return List of NodeThreadInfo
     */
    private List<NodeThreadInfo> buildThreadList(Map<Long, Long> delta) {
        List<NodeThreadInfo> threads = new ArrayList<>();
        double elapsedUptime = runtimeMxBean.getUptime() - lastCycle;
        double totalElapsedUptime = elapsedUptime * operatingSystemMXBean.getAvailableProcessors();
        for (Thread thread : nodeStats.getThreads()) {
            if (thread == null) {
                continue;
            }

            double cpuUsage = (delta.getOrDefault(thread.getId(), 0L) / 1000000d) / totalElapsedUptime * 100;
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
