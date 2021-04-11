package freenet.node.diagnostics.diagnostics.threads;

import freenet.node.*;
import freenet.node.diagnostics.diagnostics.*;
import freenet.support.*;

import java.lang.management.*;
import java.util.*;

/**
 * Runnable thread to retrieve node thread's information and compiling it into
 * an array of NodeThreadInfo objects.
 */
public class ThreadsDiagnostics implements Runnable, Diagnostics {
    private final String name;
    private final int monitorInterval;

    private final NodeStats nodeStats;
    private final Ticker ticker;

    /** Interval in ms the ThreadMonitor is launched */
    private static final int MONITOR_INTERVAL = 10000;
    private static final String MONITOR_THREAD_NAME = "NodeDiagnostics: thread monitor";

    /** Sleep interval to calculate % CPU used by each thread */
    private static final int CPU_SLEEP_INTERVAL = 1000;
    List<NodeThreadInfo> nodeThreadInfos    = Collections.synchronizedList(new ArrayList<>());

    OperatingSystemMXBean operatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean();
    ThreadMXBean threadMxBean               = ManagementFactory.getThreadMXBean();
    RuntimeMXBean runtimeMxBean             = ManagementFactory.getRuntimeMXBean();

    /**
     * @param nodeStats Used to retrieve data points
     * @param ticker Used to queue timed jobs
     * @param name Thread name
     * @param monitorInterval Sleep intervals to retrieve CPU usage
     */
    public ThreadsDiagnostics(NodeStats nodeStats, Ticker ticker, String name, int monitorInterval) {
        this.nodeStats = nodeStats;
        this.ticker = ticker;
        this.name = name;
        this.monitorInterval = monitorInterval;
    }

    /*
     * @param nodeStats Used to retrieve data points
     * @param ticker Used to queue timed jobs
     */
    public ThreadsDiagnostics(NodeStats nodeStats, Ticker ticker) {
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
