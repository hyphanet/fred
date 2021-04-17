package freenet.node.metrics.providers;

import freenet.node.metrics.*;
import freenet.support.*;

import java.util.*;

public class BandwidthMetricsProvider extends DefaultMetricProvider implements Runnable, MetricsProvider {
    public BandwidthMetricsProvider(Ticker ticker) {
        super(ticker);
    }

    public List<Metric> update() {
        return null;
    }
}
