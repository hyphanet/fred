package freenet.clients.http;

import freenet.client.HighLevelSimpleClient;
import freenet.l10n.L10n;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.PeerNodeStatus;
import freenet.support.HTMLNode;
import freenet.support.SimpleFieldSet;

public class OpennetConnectionsToadlet extends ConnectionsToadlet implements LinkEnabledCallback {

	protected OpennetConnectionsToadlet(Node n, NodeClientCore core, HighLevelSimpleClient client) {
		super(n, core, client);
	}

	protected void drawNameColumn(HTMLNode peerRow,
			PeerNodeStatus peerNodeStatus) {
		// Do nothing - no names on opennet
	}

	protected void drawPrivateNoteColumn(HTMLNode peerRow,
			PeerNodeStatus peerNodeStatus, boolean fProxyJavascriptEnabled) {
		// Do nothing - no private notes either (no such thing as negative trust in cyberspace)
	}

	protected boolean hasNameColumn() {
		return false;
	}

	protected boolean hasPrivateNoteColumn() {
		return false;
	}

	protected SimpleFieldSet getNoderef() {
		return node.exportOpennetPublicFieldSet();
	}

	protected PeerNodeStatus[] getPeerNodeStatuses() {
		return node.peers.getOpennetPeerNodeStatuses();
	}

	public boolean isEnabled() {
		return node.isOpennetEnabled();
	}

	protected String getPageTitle(String titleCountString, String myName) {
		return L10n.getString("OpennetConnectionsToadlet.fullTitle", new String[] { "counts", "name" }, new String[] { titleCountString, node.getMyName() } );
	}

}
