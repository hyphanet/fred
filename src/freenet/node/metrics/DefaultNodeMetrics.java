package freenet.node.metrics;

import freenet.node.*;
import freenet.node.metrics.peers.*;
import freenet.support.*;

import java.util.*;

public class DefaultNodeMetrics implements NodeMetrics {
    private final List<MetricsProvider> providers = new ArrayList<>();
    private final DefaultNodeMetricsPersister nodeMetricsPersister;

    public DefaultNodeMetrics(Node node) {
        PeerManager peers = node.peers;
        Ticker ticker = node.ticker;
        providers.add(new DefaultPeerMetrics(peers, ticker));
        nodeMetricsPersister = new DefaultNodeMetricsPersister(providers, ticker);
    }

    public void start() {
        Logger.error(this, "Starting default node metrics");
        providers.forEach(MetricsProvider::start);
        nodeMetricsPersister.start();
    }
}
