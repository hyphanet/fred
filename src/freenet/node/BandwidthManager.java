package freenet.node;

import freenet.pluginmanager.FredPluginBandwidthIndicator;

import static java.util.concurrent.TimeUnit.*;

public class BandwidthManager {

    private static final long DELAY_HOURS = 1;

    private final Node node;

    BandwidthManager(Node node) {
        this.node = node;
    }

    public void start() {
        node.ticker.queueTimedJob(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!node.config.get("node").getBoolean("connectionSpeedDetection"))
                        return;

                    FredPluginBandwidthIndicator bandwidthIndicator = node.ipDetector.getBandwidthIndicator();
                    System.out.println(bandwidthIndicator.getClass());
                    System.out.println("    DownstreamMaxBitRate: " + bandwidthIndicator.getDownstreamMaxBitRate());
                } catch (Exception e) {
                    e.printStackTrace();
                    throw e;
                } finally {
                    node.ticker.queueTimedJob(this, SECONDS.toMillis(DELAY_HOURS)); // TODO: change to hours
                }
            }
        }, SECONDS.toMillis(DELAY_HOURS)); // TODO: change to hours
    }
}
