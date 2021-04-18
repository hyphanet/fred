package freenet.node.metrics.providers;

import freenet.config.*;
import freenet.node.*;
import freenet.node.metrics.*;
import freenet.support.*;

import java.util.*;

public class BandwidthMetricsProvider extends DefaultMetricProvider implements Runnable, MetricsProvider {
    private final Node node;
    public BandwidthMetricsProvider(Node node, Ticker ticker) {
        super(ticker);
        this.node = node;
    }

    public List<Metric> update() {
        List<Metric> metrics = new ArrayList<>();

        final long now = System.currentTimeMillis();
        final long nodeUptimeSeconds = (now - node.startupTime) / 1000;
        long[] total = node.collector.getTotalIO();
        if(total[0] == 0 || total[1] == 0) {
            return null;
        }
        long total_output_rate = (total[0]) / nodeUptimeSeconds;
        long total_input_rate = (total[1]) / nodeUptimeSeconds;
        long totalPayload = node.getTotalPayloadSent();

        long total_payload_rate = totalPayload / nodeUptimeSeconds;
        if (node.clientCore == null) {
            return null;
        }

        BandwidthStatsContainer stats = node.clientCore.bandwidthStatsPutter.getLatestBWData();
        if (stats == null) {
            return null;
        }

        long overall_total_out = stats.totalBytesOut;
        long overall_total_in = stats.totalBytesIn;
        int percent = (int) (100 * totalPayload / total[0]);
        long[] rate = node.nodeStats.getNodeIOStats();
        long delta = (rate[5] - rate[2]) / 1000;
        if(delta > 0) {
            long output_rate = (rate[3] - rate[0]) / delta;
            addMetric(metrics, "session.output.rate", output_rate);
            long input_rate = (rate[4] - rate[1]) / delta;
            addMetric(metrics, "session.input.rate", output_rate);
        }
        addMetric(metrics, "session.output", total[0]);
        addMetric(metrics, "session.input", total[1]);
        addMetric(metrics, "payload.output", totalPayload);
        addMetric(metrics, "global.input", overall_total_in);
        addMetric(metrics, "global.output", overall_total_out);

        long totalBytesSentCHKRequests = node.nodeStats.getCHKRequestTotalBytesSent();
        addMetric(metrics, "sent.chk_requests", totalBytesSentCHKRequests);
        long totalBytesSentSSKRequests = node.nodeStats.getSSKRequestTotalBytesSent();
        addMetric(metrics, "sent.ssk_requests", totalBytesSentSSKRequests);
        long totalBytesSentCHKInserts = node.nodeStats.getCHKInsertTotalBytesSent();
        addMetric(metrics, "sent.chk_inserts", totalBytesSentCHKInserts);
        long totalBytesSentSSKInserts = node.nodeStats.getSSKInsertTotalBytesSent();
        addMetric(metrics, "sent.ssk_inserts", totalBytesSentSSKInserts);
        long totalBytesSentOfferedKeys = node.nodeStats.getOfferedKeysTotalBytesSent();
        addMetric(metrics, "sent.offered_keys", totalBytesSentOfferedKeys);
        long totalBytesSendOffers = node.nodeStats.getOffersSentBytesSent();
        addMetric(metrics, "sent.offers", totalBytesSendOffers);
        long totalBytesSentSwapOutput = node.nodeStats.getSwappingTotalBytesSent();
        addMetric(metrics, "sent.swapping", totalBytesSentSwapOutput);
        long totalBytesSentAuth = node.nodeStats.getTotalAuthBytesSent();
        addMetric(metrics, "sent.auth", totalBytesSentAuth);
        long totalBytesSentAckOnly = node.nodeStats.getNotificationOnlyPacketsSentBytes();
        addMetric(metrics, "sent.notification_only_packets", totalBytesSentAckOnly);
        long totalBytesSentResends = node.nodeStats.getResendBytesSent();
        addMetric(metrics, "sent.resend", totalBytesSentResends);
        long totalBytesSentUOM = node.nodeStats.getUOMBytesSent();
        addMetric(metrics, "sent.uom", totalBytesSentUOM);
        long totalBytesSentAnnounce = node.nodeStats.getAnnounceBytesSent();
        addMetric(metrics, "sent.announce", totalBytesSentAnnounce);
        long totalBytesSentAnnouncePayload = node.nodeStats.getAnnounceBytesPayloadSent();
        addMetric(metrics, "sent.announce_bytes_payload", totalBytesSentAnnouncePayload);
        long totalBytesSentRoutingStatus = node.nodeStats.getRoutingStatusBytes();
        addMetric(metrics, "sent.routing_status", totalBytesSentRoutingStatus);
        long totalBytesSentNetworkColoring = node.nodeStats.getNetworkColoringSentBytes();
        addMetric(metrics, "sent.network_coloring", totalBytesSentNetworkColoring);
        long totalBytesSentPing = node.nodeStats.getPingSentBytes();
        addMetric(metrics, "sent.ping", totalBytesSentPing);
        long totalBytesSentProbeRequest = node.nodeStats.getProbeRequestSentBytes();
        addMetric(metrics, "sent.probe_request", totalBytesSentProbeRequest);
        long totalBytesSentRouted = node.nodeStats.getRoutedMessageSentBytes();
        addMetric(metrics, "sent.routed_message", totalBytesSentRouted);
        long totalBytesSentDisconn = node.nodeStats.getDisconnBytesSent();
        addMetric(metrics, "sent.disconn", totalBytesSentDisconn);
        long totalBytesSentInitial = node.nodeStats.getInitialMessagesBytesSent();
        addMetric(metrics, "sent.initial_message", totalBytesSentInitial);
        long totalBytesSentChangedIP = node.nodeStats.getChangedIPBytesSent();
        addMetric(metrics, "sent.changed_ip", totalBytesSentChangedIP);
        long totalBytesSentNodeToNode = node.nodeStats.getNodeToNodeBytesSent();
        addMetric(metrics, "sent.node_to_node", totalBytesSentNodeToNode);
        long totalBytesSentAllocationNotices = node.nodeStats.getAllocationNoticesBytesSent();
        addMetric(metrics, "sent.allocation_notices", totalBytesSentAllocationNotices);
        long totalBytesSentFOAF = node.nodeStats.getFOAFBytesSent();
        addMetric(metrics, "sent.foaf", totalBytesSentFOAF);

        return metrics;
    }
    private void addMetric(List<Metric> metrics, String name, long value) {
        metrics.add(new Metric("bandwidth." + name, (int)value));
    }
}
