package freenet.node.metrics.providers;

import freenet.node.metrics.*;
import freenet.support.*;

import java.util.*;

public class DatastoreMetricsProvider extends DefaultMetricProvider implements Runnable, MetricsProvider {
    public DatastoreMetricsProvider(Ticker ticker) {
        super(ticker);
    }

    public List<Metric> update() {
        return null;
    }
}
