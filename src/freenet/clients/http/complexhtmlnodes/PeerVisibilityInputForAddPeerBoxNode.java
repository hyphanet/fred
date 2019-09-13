package freenet.clients.http.complexhtmlnodes;

import freenet.l10n.NodeL10n;
import freenet.node.DarknetPeerNode;
import freenet.support.HTMLNode;

public class PeerVisibilityInputForAddPeerBoxNode extends HTMLNode {

    public PeerVisibilityInputForAddPeerBoxNode(String l10nPrefix) {
        super("div");

        this.addChild("b", l10n(l10nPrefix + "peerVisibilityTitle"));
        this.addChild("#", " ");
        this.addChild("#", l10n(l10nPrefix + "peerVisibilityIntroduction"));
        for (DarknetPeerNode.FRIEND_VISIBILITY visibility : DarknetPeerNode.FRIEND_VISIBILITY.values()) { // FIXME reverse order
            HTMLNode input = this.addChild("br")
                    .addChild("input",
                            new String[] { "type", "name", "value" },
                            new String[] { "radio", "visibility", visibility.name() });
            if (visibility.isDefaultValue())
                input.addAttribute("checked", "checked");
            input.addChild("b", l10n(l10nPrefix + "peerVisibility."+visibility.name()));
            input.addChild("#", ": ");
            input.addChild("#", l10n(l10nPrefix + "peerVisibilityExplain."+visibility.name()));
        }
        this.addChild("br");
    }

    private String l10n(String key) {
        return NodeL10n.getBase().getString(key);
    }
}
