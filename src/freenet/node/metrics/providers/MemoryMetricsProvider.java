package freenet.node.metrics.providers;

import freenet.node.metrics.*;
import freenet.support.*;

import java.util.*;

public class MemoryMetricsProvider extends DefaultMetricProvider implements Runnable, MetricsProvider {
    public MemoryMetricsProvider(Ticker ticker) {
        super(ticker);
    }

    public List<Metric> update() {
        Runtime rt = Runtime.getRuntime();
        long freeMemory = rt.freeMemory();
        long totalMemory = rt.totalMemory();
        long maxMemory = rt.maxMemory();
        long usedJavaMem = totalMemory - freeMemory;

        List<Metric> metrics = new ArrayList<>();
        metrics.add(new Metric("memory.used", (int)usedJavaMem));
        metrics.add(new Metric("memory.max", (int)maxMemory));
        metrics.add(new Metric("memory.total", (int)totalMemory));
        metrics.add(new Metric("memory.free", (int)freeMemory));

        return metrics;
    }
}
