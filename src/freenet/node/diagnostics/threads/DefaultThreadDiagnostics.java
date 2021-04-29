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
import java.util.stream.*;

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

    private final ThreadMXBean threadMxBean               = ManagementFactory.getThreadMXBean();

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

    @Override
    public void run() {
        nodeThreadInfo.set(
            Arrays.stream(nodeStats.getThreads())
            .filter(Objects::nonNull)
            .map(thread ->
                new NodeThreadInfo(
                    thread.getId(),
                    thread.getName(),
                    thread.getPriority(),
                    thread.getThreadGroup().getName(),
                    thread.getState().toString(),
                    threadMxBean.getThreadCpuTime(thread.getId())
                )
            )
            .collect(Collectors.toList())
        );

        scheduleNext();
    }

    /**
     * @return List of Node threads
     */
    public List<NodeThreadInfo> getThreads() {
        return nodeThreadInfo.get();
    }
}
