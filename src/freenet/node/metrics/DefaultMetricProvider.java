package freenet.node.metrics;

import freenet.support.*;

import java.util.*;
import java.util.concurrent.atomic.*;

abstract public class DefaultMetricProvider implements Runnable {
    protected static final int DEFAULT_INTERVAL = 10000;
    private final Ticker ticker;
    private final AtomicReference<List<Metric>> metrics = new AtomicReference<>(new ArrayList<>());

    public DefaultMetricProvider(Ticker ticker) {
        this.ticker = ticker;
    }

    protected void scheduleNext() {
        scheduleNext(DEFAULT_INTERVAL);
    }

    protected void scheduleNext(int interval) {
        ticker.queueTimedJob(
            this,
            this.getClass().getName(),
            interval,
            false,
            true
        );
    }

    public void start() {
        scheduleNext(0);
    }

    /**
     * Returns metrics snapshot and remove these metrics.
     */
    public List<Metric> getMetrics() {
        return Collections.unmodifiableList(metrics.getAndSet(new ArrayList<>()));
    }

    @Override
    public void run() {
        List<Metric> updatedMetrics = this.update();
        if (updatedMetrics == null)  {
            scheduleNext();
        }

        updatedMetrics.addAll(metrics.get());
        metrics.set(updatedMetrics);

        scheduleNext();
    }

    abstract public List<Metric> update();
}
