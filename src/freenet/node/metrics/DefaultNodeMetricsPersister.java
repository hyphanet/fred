package freenet.node.metrics;

import freenet.support.*;

import java.time.*;
import java.util.*;

public class DefaultNodeMetricsPersister implements Runnable {
    private final static int DEFAULT_INTERVAL = 1000;
    private final Ticker ticker;
    List<MetricsProvider> mps;

    public DefaultNodeMetricsPersister(List<MetricsProvider> mps, Ticker ticker) {
        this.mps = mps;
        this.ticker = ticker;
    }

    private void scheduleNext() {
        scheduleNext(DEFAULT_INTERVAL);
    }

    private void scheduleNext(int interval) {
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

    @Override
    public void run() {
        Logger.error(this, "Running node persister");
        for (MetricsProvider mp : mps) {
            for (Metric metric : mp.getMetrics()) {
                Logger.error(this, mp.getClass().getName());
                Logger.error(
                    this,
                    String.format(
                        "[%s] %s = %s",
                        metric.getTimestamp(),
                        metric.getName(),
                        metric.getValue()
                    )
                );
            }
        }

        scheduleNext();
    }
}
