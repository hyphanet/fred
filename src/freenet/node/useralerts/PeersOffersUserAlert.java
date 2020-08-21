package freenet.node.useralerts;

import freenet.clients.http.complexhtmlnodes.PeerTrustInputForAddPeerBoxNode;
import freenet.clients.http.complexhtmlnodes.PeerVisibilityInputForAddPeerBoxNode;
import freenet.config.InvalidConfigValueException;
import freenet.config.NodeNeedRestartException;
import freenet.l10n.NodeL10n;
import freenet.node.Node;
import freenet.support.HTMLNode;
import freenet.support.Logger;

import java.io.File;

public class PeersOffersUserAlert extends AbstractUserAlert {

    private final String frefFiles;

    private final Node node;

    private PeersOffersUserAlert(Node node, String frefFiles) {
        this.frefFiles = frefFiles;
        this.node = node;
    }

    public static void createAlert(Node node) {
        File[] files = node.runDir().file("peers-offers").listFiles();
        if (files != null && files.length > 0) {
            StringBuilder frefFiles = new StringBuilder();
            String prefix = "";
            for (final File file : files) {
                if (file.isFile()) {
                    String filename = file.getName();
                    if (filename.endsWith(".fref")) {
                        frefFiles.append(prefix).append(file.getName());
                        prefix = ", ";
                    }
                }
            }
            node.clientCore.alerts.register(new PeersOffersUserAlert(node, frefFiles.toString()));
        }
    }

    @Override
    public String getTitle() {
        return l10n("title");
    }

    @Override
    public HTMLNode getHTMLText() {
        HTMLNode content = new HTMLNode("div");
        content.addChild("p", l10n("text"));
        content.addChild("p", frefFiles);
        HTMLNode form = content.addChild("form",
                new String[] {"action", "method"},
                new String[] {"/friends/", "post"});
        form.addChild("input",
                new String[] {"type", "name", "value"},
                new String[] {"hidden", "formPassword", node.clientCore.formPassword});
        form.addChild("input",
                new String[] {"type", "name", "value"},
                new String[] {"hidden", "peers-offers-files", "true"});

        form.addChild(new PeerTrustInputForAddPeerBoxNode());
        form.addChild(new PeerVisibilityInputForAddPeerBoxNode());

        form.addChild("input",
                        new String[] {"type", "name", "value"},
                        new String[] {"submit", "add", "Connect"});

        return content;
    }

    @Override
    public String dismissButtonText() {
        return NodeL10n.getBase().getString("Toadlet.no");
    }

    @Override
    public void onDismiss() {
        try {
            node.config.get("node").set("peersOffersDismissed", true);
        } catch (InvalidConfigValueException | NodeNeedRestartException e) {
            if (Logger.shouldLog(Logger.LogLevel.MINOR, this))
                Logger.minor(this, e.getLocalizedMessage());
            valid = false;
        }
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
        return NodeL10n.getBase().getString("PeersOffersUserAlert." + key);
    }
}
