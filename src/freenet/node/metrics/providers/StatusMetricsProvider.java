package freenet.node.metrics.providers;

import freenet.node.metrics.*;
import freenet.support.*;

import java.util.*;

public class StatusMetricsProvider extends DefaultMetricProvider implements Runnable, MetricsProvider {
    public StatusMetricsProvider(Ticker ticker) {
        super(ticker);
    }

    public List<Metric> update() {
        return null;
    }
}
