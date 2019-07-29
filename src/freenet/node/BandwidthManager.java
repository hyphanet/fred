package freenet.node;

import freenet.clients.http.wizardsteps.BandwidthLimit;
import freenet.node.useralerts.UpgradeConnectionSpeedUserAlert;
import freenet.pluginmanager.FredPluginBandwidthIndicator;
import freenet.support.Logger;

import static java.util.concurrent.TimeUnit.*;

public class BandwidthManager {

    private static final long DELAY_HOURS = 1;

    private int lastOfferedInputBandwidth;
    private int lastOfferedOutputBandwidth;

    private final Node node;

    BandwidthManager(Node node) {
        this.node = node;
    }

    public void start() {
        // TODO: move to "on upgrade"
        /* offer upgrade of the connection speed on upgrade, if auto-detected
         * speed is much higher than the set speed, or even better: if the
         * detected speed increased significantly since the last offer. */
        node.ticker.queueTimedJob(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!node.config.get("node").getBoolean("connectionSpeedDetection")) {
                        return;
                    }

                    FredPluginBandwidthIndicator bandwidthIndicator = node.ipDetector.getBandwidthIndicator();
                    int detectedInputBandwidth = bandwidthIndicator.getDownstreamMaxBitRate() / 8;
                    int detectedOutputBandwidth = bandwidthIndicator.getUpstramMaxBitRate() / 8;

                    int currentInputBandwidth = node.config.get("node").getInt("inputBandwidthLimit");
                    int currentOutputBandwidth = node.config.get("node").getInt("outputBandwidthLimit");

                    if (detectedInputBandwidth > currentInputBandwidth * 3 &&
                            detectedInputBandwidth > lastOfferedInputBandwidth * 3 ||
                        detectedOutputBandwidth > currentOutputBandwidth * 3 &&
                                detectedOutputBandwidth > lastOfferedOutputBandwidth * 3) {
                        lastOfferedInputBandwidth = Math.max(detectedInputBandwidth / 2, currentInputBandwidth);
                        lastOfferedOutputBandwidth = Math.max(detectedOutputBandwidth / 2, currentOutputBandwidth);

                        UpgradeConnectionSpeedUserAlert.createAlert(node,
                                new BandwidthLimit(lastOfferedInputBandwidth, lastOfferedOutputBandwidth, null, false));
                    }
                } catch (Exception e) {
                    Logger.minor(this, e.getMessage());
                    throw e;
                } finally {
                    node.ticker.queueTimedJob(this, SECONDS.toMillis(DELAY_HOURS)); // TODO: change to hours
                }
            }
        }, SECONDS.toMillis(DELAY_HOURS)); // TODO: change to hours
    }
}
