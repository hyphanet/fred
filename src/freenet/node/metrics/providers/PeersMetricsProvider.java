package freenet.node.metrics.providers;

import freenet.node.*;
import freenet.node.metrics.*;
import freenet.support.*;

import java.util.*;

public class PeersMetricsProvider extends DefaultMetricProvider implements Runnable, MetricsProvider {
    private final PeerManager peers;
    private static final String DATA_POINT_PREFIX = "peers.status.";

    public PeersMetricsProvider(PeerManager peers, Ticker ticker) {
        super(ticker);
        this.peers = peers;
    }

    public List<Metric> update() {
        PeerNodeStatus[] peerNodeStatuses = peers.getPeerNodeStatuses(true);
        Arrays.sort(peerNodeStatuses, Comparator.comparingInt(PeerNodeStatus::getStatusValue));

        HashMap<Integer, String> registeredDataPoint = new HashMap<Integer, String>(){{
            put(PeerManager.PEER_NODE_STATUS_CONNECTED, "connected");
            put(PeerManager.PEER_NODE_STATUS_ROUTING_BACKED_OFF, "routing_backed_off");
            put(PeerManager.PEER_NODE_STATUS_TOO_NEW, "too_new");
            put(PeerManager.PEER_NODE_STATUS_TOO_OLD, "too_old");
            put(PeerManager.PEER_NODE_STATUS_DISCONNECTED, "disconnected");
            put(PeerManager.PEER_NODE_STATUS_NEVER_CONNECTED, "never_connected");
            put(PeerManager.PEER_NODE_STATUS_DISABLED, "disabled");
            put(PeerManager.PEER_NODE_STATUS_BURSTING, "bursting");
            put(PeerManager.PEER_NODE_STATUS_LISTENING, "listening");
            put(PeerManager.PEER_NODE_STATUS_LISTEN_ONLY, "listen_only");
            put(PeerManager.PEER_NODE_STATUS_ROUTING_DISABLED, "routing_disabled");
            put(PeerManager.PEER_NODE_STATUS_CLOCK_PROBLEM, "clock_problem");
            put(PeerManager.PEER_NODE_STATUS_CONN_ERROR, "conn_error");
            put(PeerManager.PEER_NODE_STATUS_DISCONNECTING, "disconnecting");
            put(PeerManager.PEER_NODE_STATUS_NO_LOAD_STATS, "no_load_stats");
        }};

        List<Metric> metrics = new ArrayList<>();
        registeredDataPoint.forEach((Integer status, String name) -> {
            registerDataPoint(
                metrics,
                peerNodeStatuses,
                status,
                name
            );
        });

        metrics.add(
            new Metric(getDataPointPrefix("seed_servers"), getCountSeedServers(peerNodeStatuses))
        );
        metrics.add(
            new Metric(getDataPointPrefix("seed_clients"), getCountSeedClients(peerNodeStatuses))
        );

        return metrics;
    }

    /**
     * @param metrics
     * @param peerNodeStatuses
     * @param status
     * @param datapoint
     */
    private void registerDataPoint(List<Metric> metrics, PeerNodeStatus[] peerNodeStatuses, int status, String datapoint) {
        metrics.add(
            new Metric(
                getDataPointPrefix(datapoint),
                getPeerStatusCount(peerNodeStatuses, status)
            )
        );
    }

    /**
     * @param name
     * @return
     */
    private String getDataPointPrefix(String name) {
        return DATA_POINT_PREFIX + name;
    }

    /**
     * Counts the peers in <code>peerNodes</code> that have the specified
     * status.
     * @param peerNodeStatuses The peer nodes' statuses
     * @param status The status to count
     * @return The number of peers that have the specified status.
     */
    private int getPeerStatusCount(PeerNodeStatus[] peerNodeStatuses, int status) {
        int count = 0;
        for (PeerNodeStatus peerNodeStatus: peerNodeStatuses) {
            if(!peerNodeStatus.recordStatus())
                continue;
            if (peerNodeStatus.getStatusValue() == status) {
                count++;
            }
        }
        return count;
    }

    /**
     * @param peerNodeStatuses
     * @return
     */
    private int getCountSeedServers(PeerNodeStatus[] peerNodeStatuses) {
        int count = 0;
        for (PeerNodeStatus peerNodeStatus: peerNodeStatuses) {
            if(peerNodeStatus.isSeedServer()) count++;
        }
        return count;
    }

    /**
     * @param peerNodeStatuses
     * @return
     */
    private int getCountSeedClients(PeerNodeStatus[] peerNodeStatuses) {
        int count = 0;
        for (PeerNodeStatus peerNodeStatus: peerNodeStatuses) {
            if(peerNodeStatus.isSeedClient()) count++;
        }
        return count;
    }
}
