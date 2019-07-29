package freenet.node.useralerts;

import freenet.clients.http.wizardsteps.BandwidthLimit;
import freenet.l10n.NodeL10n;
import freenet.node.Node;
import freenet.support.HTMLNode;
import freenet.support.SizeUtil;

public class UpgradeConnectionSpeedUserAlert extends AbstractUserAlert {

    private final Node node;
    private BandwidthLimit bandwidthLimit;

    private UpgradeConnectionSpeedUserAlert(Node node, BandwidthLimit bandwidthLimit) {
        this.node = node;
        this.bandwidthLimit = bandwidthLimit;
    }

    public static void createAlert(Node node, BandwidthLimit bandwidthLimit) {
        node.clientCore.alerts.register(
                new UpgradeConnectionSpeedUserAlert(node, bandwidthLimit));
    }

    @Override
    public String getTitle() {
        return l10n("title");
    }

    @Override
    public HTMLNode getHTMLText() {
        HTMLNode content = new HTMLNode("div");
        content.addChild("p", l10n("text"));
        content.addChild("p", l10n("currentBandwidthLimit",
                new String[] {"input", "output"},
                new String[] {
                        SizeUtil.formatSize(node.config.get("node").getInt("inputBandwidthLimit")),
                        SizeUtil.formatSize(node.config.get("node").getInt("outputBandwidthLimit"))}));
        HTMLNode form = content.addChild("form",
                new String[] {"action", "method"},
                new String[] {"/", "post"}); // TODO: add endpoint
        form.addChild("span", l10n("downloadLimit"));
        form.addChild("input",
                new String[] {"type", "name", "value"},
                new String[] {"text", "inputBandwidthLimit", SizeUtil.formatSize(bandwidthLimit.downBytes)});
        form.addChild("br");
        form.addChild("span", l10n("uploadLimit"));
        form.addChild("input",
                new String[] {"type", "name", "value"},
                new String[] {"text", "outputBandwidthLimit", SizeUtil.formatSize(bandwidthLimit.upBytes)});
        form.addChild("input",
                new String[] {"type", "name", "value"},
                new String[] {"hidden", "formPassword", node.clientCore.formPassword});
        form.addChild("input",
                new String[] {"type", "value"},
                new String[] {"submit", "Upgrade"});

        return content;
    }

    @Override
    public String dismissButtonText() {
        return NodeL10n.getBase().getString("Toadlet.no");
    }

    @Override
    public boolean userCanDismiss() {
        return true;
    }

    @Override
    public boolean shouldUnregisterOnDismiss() {
        return true;
    }

    private String l10n(String key) {
        return NodeL10n.getBase().getString("UpgradeConnectionSpeedUserAlert." + key);
    }

    private String l10n(String key, String[] patterns, String[] values) {
        return NodeL10n.getBase().getString("UpgradeConnectionSpeedUserAlert." + key, patterns, values);
    }
}
