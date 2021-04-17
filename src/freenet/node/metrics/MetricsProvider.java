package freenet.node.metrics;

import java.util.*;

public interface MetricsProvider {
    void start();
    List<Metric> update();
    List<Metric> getMetrics();
}
