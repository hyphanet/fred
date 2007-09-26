package freenet.clients.http;

import java.util.Comparator;

import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.ConnectionsToadlet.ComparatorByStatus;
import freenet.clients.http.DarknetConnectionsToadlet.DarknetComparator;
import freenet.l10n.L10n;
import freenet.node.DarknetPeerNodeStatus;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.OpennetPeerNodeStatus;
import freenet.node.PeerNodeStatus;
import freenet.support.HTMLNode;
import freenet.support.SimpleFieldSet;
import freenet.support.TimeUtil;

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

	protected boolean shouldDrawNoderefBox(boolean advancedModeEnabled) {
		// Developers may want to see the noderef.
		// Users as well until the announcement protocol is implemented
		return true;
	}

	protected boolean showPeerActionsBox() {
		// No per-peer actions supported on opennet - there's no point, they'll only reconnect,
		// possibly as a different identity. And we don't want to be able to send N2NTM spam either.
		return false;
	}

	protected void drawPeerActionSelectBox(HTMLNode peerForm, boolean advancedModeEnabled) {
		// Do nothing, see showPeerActionsBox().
	}

	protected String getPeerListTitle() {
		return L10n.getString("OpennetConnectionsToadlet.peersListTitle");
	}

	protected boolean acceptRefPosts() {
		return true;
	}

	protected String defaultRedirectLocation() {
		return "/opennet/";
	}

	protected boolean isOpennet() {
		return true;
	}

	protected class OpennetComparator extends ComparatorByStatus {

		OpennetComparator(String sortBy, boolean reversed) {
			super(sortBy, reversed);
		}
	
		protected int customCompare(PeerNodeStatus firstNode, PeerNodeStatus secondNode, String sortBy) {
			if(sortBy.equals("successTime")) {
				long t1 = ((OpennetPeerNodeStatus)firstNode).timeLastSuccess;
				long t2 = ((OpennetPeerNodeStatus)secondNode).timeLastSuccess;
				if(t1 > t2) return reversed ? 1 : -1;
				else if(t2 > t1) return reversed ? -1 : 1;
			}
			return super.customCompare(firstNode, secondNode, sortBy);
		}
	}
	
	protected Comparator comparator(String sortBy, boolean reversed) {
		return new OpennetComparator(sortBy, reversed);
	}

	SimpleColumn[] endColumnHeaders() {
		return new SimpleColumn[] { 
				new SimpleColumn() {

					protected void drawColumn(HTMLNode peerRow, PeerNodeStatus peerNodeStatus) {
						OpennetPeerNodeStatus status = (OpennetPeerNodeStatus) peerNodeStatus;
						peerRow.addChild("td", "class", "peer-last-success", TimeUtil.formatTime(System.currentTimeMillis() - status.timeLastSuccess));
					}
					public String getExplanationKey() {
						return "OpennetConnectionsToadlet.successTime";
					}
					public String getSortString() {
						return "successTime";
					}
					public String getTitleKey() {
						return "OpennetConnectionsToadlet.successTimeTitle";
					}
				}};
	}
		
}
