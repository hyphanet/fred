package freenet.clients.http;

import java.io.IOException;
import java.net.URI;
import java.util.Comparator;

import freenet.client.HighLevelSimpleClient;
import freenet.config.ConfigException;
import freenet.l10n.NodeL10n;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.OpennetPeerNodeStatus;
import freenet.node.PeerNodeStatus;
import freenet.support.HTMLNode;
import freenet.support.SimpleFieldSet;
import freenet.support.TimeUtil;
import freenet.support.api.HTTPRequest;

public class OpennetConnectionsToadlet extends ConnectionsToadlet implements LinkEnabledCallback {

	protected OpennetConnectionsToadlet(Node n, NodeClientCore core, HighLevelSimpleClient client) {
		super(n, core, client);
	}

	@Override
	protected void drawNameColumn(HTMLNode peerRow,
			PeerNodeStatus peerNodeStatus, boolean advanced) {
		// Do nothing - no names on opennet
	}

	@Override
	protected void drawPrivateNoteColumn(HTMLNode peerRow,
			PeerNodeStatus peerNodeStatus, boolean fProxyJavascriptEnabled) {
		// Do nothing - no private notes either (no such thing as negative trust in cyberspace)
	}

	@Override
	protected boolean hasNameColumn() {
		return false;
	}

	@Override
	protected boolean hasPrivateNoteColumn() {
		return false;
	}

	@Override
	protected SimpleFieldSet getNoderef() {
		return node.exportOpennetPublicFieldSet();
	}

	@Override
	protected PeerNodeStatus[] getPeerNodeStatuses(boolean noHeavy) {
		return node.peers.getOpennetPeerNodeStatuses(noHeavy);
	}

	@Override
	public boolean isEnabled(ToadletContext ctx) {
		return node.isOpennetEnabled();
	}

	@Override
	protected String getPageTitle(String titleCountString) {
		return NodeL10n.getBase().getString("OpennetConnectionsToadlet.fullTitle",
				new String[] {"counts"}, new String[] {titleCountString} );
	}

	@Override
	protected boolean shouldDrawNoderefBox(boolean advancedModeEnabled) {
		return advancedModeEnabled;
	}

	@Override
	protected boolean showPeerActionsBox() {
		// No per-peer actions supported on opennet - there's no point, they'll only reconnect,
		// possibly as a different identity. And we don't want to be able to send N2NTM spam either.
		return false;
	}

	@Override
	protected void drawPeerActionSelectBox(HTMLNode peerForm, boolean advancedModeEnabled) {
		// Do nothing, see showPeerActionsBox().
	}

	@Override
	protected String getPeerListTitle() {
		return NodeL10n.getBase().getString("OpennetConnectionsToadlet.peersListTitle");
	}

	@Override
	protected boolean acceptRefPosts() {
		return true;
	}

	@Override
	protected String defaultRedirectLocation() {
		return "/opennet/";
	}

	@Override
	protected boolean isOpennet() {
		return true;
	}

	protected class OpennetComparator extends ComparatorByStatus {

		OpennetComparator(String sortBy, boolean reversed) {
			super(sortBy, reversed);
		}
	
		@Override
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
	
	@Override
	protected Comparator<PeerNodeStatus> comparator(String sortBy, boolean reversed) {
		return new OpennetComparator(sortBy, reversed);
	}

	@Override
	SimpleColumn[] endColumnHeaders(boolean advancedMode) {
		if(!advancedMode) return null;
		return new SimpleColumn[] { 
				new SimpleColumn() {

					@Override
					protected void drawColumn(HTMLNode peerRow, PeerNodeStatus peerNodeStatus) {
						OpennetPeerNodeStatus status = (OpennetPeerNodeStatus) peerNodeStatus;
						long tLastSuccess = status.timeLastSuccess;
						peerRow.addChild("td", "class", "peer-last-success", tLastSuccess > 0 ? TimeUtil.formatTime(System.currentTimeMillis() - tLastSuccess) : "NEVER");
					}
					@Override
					public String getExplanationKey() {
						return "OpennetConnectionsToadlet.successTime";
					}
					@Override
					public String getSortString() {
						return "successTime";
					}
					@Override
					public String getTitleKey() {
						return "OpennetConnectionsToadlet.successTimeTitle";
					}
				}};
	}

	@Override
	public String path() {
		return "/strangers/";
	}

	@Override
	public void handleMethodGET(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException, 	RedirectException {
		super.handleMethodGET(uri, request, ctx);
	}

	@Override
	public void handleMethodPOST(URI uri, HTTPRequest request, ToadletContext ctx)
			throws ToadletContextClosedException, IOException, RedirectException, ConfigException {
		super.handleMethodPOST(uri, request, ctx);
	}
		
}
