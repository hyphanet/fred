package freenet.node.useralerts;

import freenet.clients.http.ConnectionsToadlet;
import freenet.config.InvalidConfigValueException;
import freenet.config.NodeNeedRestartException;
import freenet.node.Node;
import freenet.support.HTMLNode;

public class PeersOffersUserAlert extends AbstractUserAlert {

    private final String frefFiles;

    private final Node node;

    public PeersOffersUserAlert(String frefFiles, Node node) {
        this.frefFiles = frefFiles;
        this.node = node;
    }

    @Override
    public String getTitle() {
        return "peers-offers/*.fref files";
    }

    @Override
    public HTMLNode getHTMLText() {
        HTMLNode content = new HTMLNode("div");
        content.addChild("p",
                "If there are *.fref files in a peers-offers/ folder, ask user whether " +
                "to connect to them. That would be a step towards introduction bundles: " +
                "You can manually build an installer which auto-connects to you.");
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

        ConnectionsToadlet.drawPeerTrustInputForAddPeerBox(form);
        ConnectionsToadlet.drawPeerVisibilityInputForAddPeerBox(form);

        form.addChild("input",
                        new String[] {"type", "name", "value"},
                        new String[] {"submit", "add", "Connect"});

        return content;
    }

    @Override
    public boolean isValid() {
        if (node.config.get("node").getBoolean("peersOffersDismissed"))
            valid = false;
        return valid;
    }

    @Override
    public String dismissButtonText() {
        return "No, thanks";
    }

    @Override
    public void onDismiss() {
        try {
            node.config.get("node").set("peersOffersDismissed", true);
        } catch (InvalidConfigValueException | NodeNeedRestartException e) {
            e.printStackTrace();
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
}
