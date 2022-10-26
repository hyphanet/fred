package freenet.node;

import freenet.clients.http.wizardsteps.BandwidthLimit;
import freenet.config.InvalidConfigValueException;
import freenet.l10n.NodeL10n;
import freenet.node.useralerts.UpgradeConnectionSpeedUserAlert;
import freenet.pluginmanager.FredPluginBandwidthIndicator;
import freenet.support.Logger;

import static java.util.concurrent.TimeUnit.*;

public class BandwidthManager {

    private static final long DELAY_HOURS = 24;

    private int lastOfferedInputBandwidth;
    private int lastOfferedOutputBandwidth;

    private final Node node;

    BandwidthManager(Node node) {
        this.node = node;
    }

    public void start() {
        // TODO: move to "on upgrade"?
        /* offer upgrade of the connection speed on upgrade, if auto-detected
         * speed is much higher than the set speed, or even better: if the
         * detected speed increased significantly since the last offer. */
        node.ticker.queueTimedJob(new Runnable() {
            @Override
            public void run() {
                try {
                    FredPluginBandwidthIndicator bandwidthIndicator = node.ipDetector.getBandwidthIndicator();
                    if (!node.config.get("node").getBoolean("connectionSpeedDetection") ||
                            bandwidthIndicator == null) {
                        return;
                    }

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
                    node.ticker.queueTimedJob(this, HOURS.toMillis(DELAY_HOURS));
                }
            }
        }, HOURS.toMillis(DELAY_HOURS));
    }

    public static void checkOutputBandwidthLimit(int obwLimit) throws InvalidConfigValueException {
        if (obwLimit <= 0) {
            throw new InvalidConfigValueException(NodeL10n.getBase().getString("Node.bwlimitMustBePositive"));
        }

        if (obwLimit < Node.getMinimumBandwidth()) {
            throw lowBandwidthLimit(obwLimit);
        }

        // Fixme: Node outputThrottle.changeNanosAndBucketSize(SECONDS.toNanos(1) / obwLimit, ...
        if (obwLimit > SECONDS.toNanos(1)) {
            throw new InvalidConfigValueException(
                    NodeL10n.getBase().getString("Node.outputBwlimitMustBeLessThan",
                            "max", Long.toString(SECONDS.toNanos(1))));
        }
    }

    public static void checkInputBandwidthLimit(int ibwLimit) throws InvalidConfigValueException {
        if (ibwLimit == -1) { // Reserved value for limit based on output limit.
            return;
        }

        if(ibwLimit <= 1) {
            throw new InvalidConfigValueException(
                    NodeL10n.getBase().getString("Node.bandwidthLimitMustBePositiveOrMinusOne"));
        }

        if (ibwLimit < Node.getMinimumBandwidth()) {
            throw lowBandwidthLimit(ibwLimit);
        }
    }

    /**
     * Returns an exception with an explanation that the given bandwidth limit is too low.
     *
     * See the Node.bandwidthMinimum localization string.
     * @param limit Bandwidth limit in bytes.
     */
    private static InvalidConfigValueException lowBandwidthLimit(int limit) {
        return new InvalidConfigValueException(NodeL10n.getBase().getString("Node.bandwidthMinimum",
                new String[] { "limit", "minimum" },
                new String[] { Integer.toString(limit), Integer.toString(Node.getMinimumBandwidth()) }));
    }
}
