package freenet.node.useralerts;

import freenet.clients.http.wizardsteps.BandwidthLimit;
import freenet.l10n.NodeL10n;
import freenet.node.Node;
import freenet.support.HTMLNode;
import freenet.support.SizeUtil;

public class UpgradeConnectionSpeedUserAlert extends AbstractUserAlert {

    private final Node node;
    private BandwidthLimit bandwidthLimit;
    private boolean upgraded;
    private String error;

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

        if (upgraded) {
            content.addChild("p", l10n("upgraded"));
            return content;
        }
        content.addChild("p", l10n("text",
                new String[] {"input", "output"},
                new String[] {
                        SizeUtil.formatSize(node.config.get("node").getInt("inputBandwidthLimit")),
                        SizeUtil.formatSize(node.config.get("node").getInt("outputBandwidthLimit"))}));
        if (error != null) {
            content.addChild("p", error);
            error = null;
        }
        HTMLNode form = content.addChild("form",
                new String[] {"action", "method"},
                new String[] {"/", "post"});
        HTMLNode bandwidthInput = form.addChild("div",
                new String[] {"style"},
                new String[] {"display: inline-block; text-align: right;"});
        bandwidthInput.addChild("span", "style", "margin-right: .5em;", l10n("downloadLimit"));
        bandwidthInput.addChild("input",
                new String[] {"type", "name", "value"},
                new String[] {"text", "inputBandwidthLimit", SizeUtil.formatSize(bandwidthLimit.downBytes)});
        bandwidthInput.addChild("br");
        bandwidthInput.addChild("span", "style", "margin-right: .5em;", l10n("uploadLimit"));
        bandwidthInput.addChild("input",
                new String[] {"type", "name", "value"},
                new String[] {"text", "outputBandwidthLimit", SizeUtil.formatSize(bandwidthLimit.upBytes)});

        form.addChild("input",
                new String[] {"type", "name", "value"},
                new String[] {"hidden", "upgradeConnectionSpeed", "upgradeConnectionSpeed"});
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
        return upgraded ? NodeL10n.getBase().getString("Toadlet.ok") : NodeL10n.getBase().getString("Toadlet.no");
    }

    @Override
    public boolean userCanDismiss() {
        return true;
    }

    @Override
    public boolean shouldUnregisterOnDismiss() {
        return true;
    }

    public void setError(String error) {
        this.error = error;
    }

    public void setUpgraded(boolean upgraded) {
        this.upgraded = upgraded;
    }

    private String l10n(String key) {
        return NodeL10n.getBase().getString("UpgradeConnectionSpeedUserAlert." + key);
    }

    private String l10n(String key, String[] patterns, String[] values) {
        return NodeL10n.getBase().getString("UpgradeConnectionSpeedUserAlert." + key, patterns, values);
    }
}
