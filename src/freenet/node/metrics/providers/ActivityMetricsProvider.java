package freenet.node.metrics.providers;

import freenet.node.metrics.*;
import freenet.support.*;

import java.util.*;

public class ActivityMetricsProvider extends DefaultMetricProvider implements Runnable, MetricsProvider {
    public ActivityMetricsProvider(Ticker ticker) {
        super(ticker);
    }

    public List<Metric> update() { return null;}
}
