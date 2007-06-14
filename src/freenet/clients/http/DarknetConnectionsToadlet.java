package freenet.clients.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import freenet.client.HighLevelSimpleClient;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.io.xfer.PacketThrottle;
import freenet.l10n.L10n;
import freenet.node.FSParseException;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.NodeStats;
import freenet.node.PeerManager;
import freenet.node.PeerNode;
import freenet.node.PeerNodeStatus;
import freenet.node.Version;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.SimpleFieldSet;
import freenet.support.SizeUtil;
import freenet.support.TimeUtil;
import freenet.support.api.HTTPRequest;

public class DarknetConnectionsToadlet extends Toadlet {
	
	private final Node node;
	private final NodeClientCore core;
	private final NodeStats stats;
	private final PeerManager peers;
	private boolean isReversed = false;
	
	protected DarknetConnectionsToadlet(Node n, NodeClientCore core, HighLevelSimpleClient client) {
		super(client);
		this.node = n;
		this.core = core;
		this.stats = n.nodeStats;
		this.peers = n.peers;
	}

	public String supportedMethods() {
		return "GET, POST";
	}

	public void handleGet(URI uri, final HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		String path = uri.getPath();
		if(path.endsWith("myref.fref")) {
			SimpleFieldSet fs = node.exportPublicFieldSet();
			StringWriter sw = new StringWriter();
			fs.writeTo(sw);
			MultiValueTable extraHeaders = new MultiValueTable();
			// Force download to disk
			extraHeaders.put("Content-Disposition", "attachment; filename=myref.fref");
			this.writeReply(ctx, 200, "application/x-freenet-reference", "OK", extraHeaders, sw.toString());
			return;
		}

		if(path.endsWith("myref.txt")) {
			SimpleFieldSet fs = node.exportPublicFieldSet();
			StringWriter sw = new StringWriter();
			fs.writeTo(sw);
			this.writeReply(ctx, 200, "text/plain", "OK", sw.toString());
			return;
		}
		
		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, "Unauthorized", L10n.getString("Toadlet.unauthorized"));
			return;
		}
		
		final boolean advancedModeEnabled = node.isAdvancedModeEnabled();
		final boolean fProxyJavascriptEnabled = node.isFProxyJavascriptEnabled();
		
		/* gather connection statistics */
		PeerNodeStatus[] peerNodeStatuses = peers.getPeerNodeStatuses();
		Arrays.sort(peerNodeStatuses, new Comparator() {
			public int compare(Object first, Object second) {
				int result = 0;
				boolean isSet = true;
				PeerNodeStatus firstNode = (PeerNodeStatus) first;
				PeerNodeStatus secondNode = (PeerNodeStatus) second;
				
				if(request.isParameterSet("sortBy")){
					final String sortBy = request.getParam("sortBy"); 

					if(sortBy.equals("name")){
						result = firstNode.getName().compareToIgnoreCase(secondNode.getName());
					}else if(sortBy.equals("address")){
						result = firstNode.getPeerAddress().compareToIgnoreCase(secondNode.getPeerAddress());
					}else if(sortBy.equals("location")){
						result = (firstNode.getLocation() - secondNode.getLocation()) < 0 ? -1 : 1; // Shouldn't be equal anyway
					}else if(sortBy.equals("version")){
						result = Version.getArbitraryBuildNumber(firstNode.getVersion()) - Version.getArbitraryBuildNumber(secondNode.getVersion());
					}else if(sortBy.equals("privnote")){
						result = firstNode.getPrivateDarknetCommentNote().compareToIgnoreCase(secondNode.getPrivateDarknetCommentNote());
					}else
						isSet=false;
				}else
					isSet=false;
				
				if(!isSet){
					int statusDifference = firstNode.getStatusValue() - secondNode.getStatusValue();
					if (statusDifference != 0) 
						result = (statusDifference < 0 ? -1 : 1);
					else
						result = firstNode.getName().compareToIgnoreCase(secondNode.getName());
				}

				if(result == 0){
					return 0;
				}else if(request.isParameterSet("reversed")){
					isReversed = true;
					return result > 0 ? -1 : 1;
				}else{
					isReversed = false;
					return result < 0 ? -1 : 1;
				}
			}
		});
		
		int numberOfConnected = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_CONNECTED);
		int numberOfRoutingBackedOff = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_ROUTING_BACKED_OFF);
		int numberOfTooNew = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_TOO_NEW);
		int numberOfTooOld = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_TOO_OLD);
		int numberOfDisconnected = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_DISCONNECTED);
		int numberOfNeverConnected = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_NEVER_CONNECTED);
		int numberOfDisabled = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_DISABLED);
		int numberOfBursting = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_BURSTING);
		int numberOfListening = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_LISTENING);
		int numberOfListenOnly = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_LISTEN_ONLY);
		
		int numberOfSimpleConnected = numberOfConnected + numberOfRoutingBackedOff;
		int numberOfNotConnected = numberOfTooNew + numberOfTooOld + numberOfDisconnected + numberOfNeverConnected + numberOfDisabled + numberOfBursting + numberOfListening + numberOfListenOnly;
		String titleCountString = null;
		if(advancedModeEnabled) {
			titleCountString = "(" + numberOfConnected + '/' + numberOfRoutingBackedOff + '/' + numberOfTooNew + '/' + numberOfTooOld + '/' + numberOfNotConnected + ')';
		} else {
			titleCountString = (numberOfNotConnected + numberOfSimpleConnected)>0 ? String.valueOf(numberOfSimpleConnected) : "";
		}
		
		HTMLNode pageNode = ctx.getPageMaker().getPageNode(L10n.getString("DarknetConnectionsToadlet.fullTitle", new String[] { "counts", "name" }, new String[] { titleCountString, node.getMyName() } ), ctx);
		HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
		
		// FIXME! We need some nice images
		long now = System.currentTimeMillis();
	
		if(ctx.isAllowedFullAccess())
			contentNode.addChild(core.alerts.createSummary());
		
		if(peerNodeStatuses.length>0){

			/* node status values */
			long nodeUptimeSeconds = (now - node.startupTime) / 1000;
			int bwlimitDelayTime = (int) stats.getBwlimitDelayTime();
			int nodeAveragePingTime = (int) stats.getNodeAveragePingTime();
			int networkSizeEstimateSession = stats.getNetworkSizeEstimate(-1);
			int networkSizeEstimateRecent = 0;
			if(nodeUptimeSeconds > (48*60*60)) {  // 48 hours
				networkSizeEstimateRecent = stats.getNetworkSizeEstimate(now - (48*60*60*1000));  // 48 hours
			}
			DecimalFormat fix4 = new DecimalFormat("0.0000");
			double routingMissDistance =  stats.routingMissDistance.currentValue();
			DecimalFormat fix1 = new DecimalFormat("##0.0%");
			double backedOffPercent =  stats.backedOffPercent.currentValue();
			String nodeUptimeString = TimeUtil.formatTime(nodeUptimeSeconds * 1000);  // *1000 to convert to milliseconds

			// BEGIN OVERVIEW TABLE
			HTMLNode overviewTable = contentNode.addChild("table", "class", "column");
			HTMLNode overviewTableRow = overviewTable.addChild("tr");
			HTMLNode nextTableCell = overviewTableRow.addChild("td", "class", "first");

			/* node status overview box */
			if(advancedModeEnabled) {
				HTMLNode overviewInfobox = nextTableCell.addChild("div", "class", "infobox");
				overviewInfobox.addChild("div", "class", "infobox-header", "Node status overview");
				HTMLNode overviewInfoboxContent = overviewInfobox.addChild("div", "class", "infobox-content");
				HTMLNode overviewList = overviewInfoboxContent.addChild("ul");
				overviewList.addChild("li", "bwlimitDelayTime:\u00a0" + bwlimitDelayTime + "ms");
				overviewList.addChild("li", "nodeAveragePingTime:\u00a0" + nodeAveragePingTime + "ms");
				overviewList.addChild("li", "networkSizeEstimateSession:\u00a0" + networkSizeEstimateSession + "\u00a0nodes");
				if(nodeUptimeSeconds > (48*60*60)) {  // 48 hours
					overviewList.addChild("li", "networkSizeEstimateRecent:\u00a0" + networkSizeEstimateRecent + "\u00a0nodes");
				}
				overviewList.addChild("li", "nodeUptime:\u00a0" + nodeUptimeString);
				overviewList.addChild("li", "routingMissDistance:\u00a0" + fix4.format(routingMissDistance));
				overviewList.addChild("li", "backedOffPercent:\u00a0" + fix1.format(backedOffPercent));
				overviewList.addChild("li", "pInstantReject:\u00a0" + fix1.format(stats.pRejectIncomingInstantly()));
				nextTableCell = overviewTableRow.addChild("td");
			}

			// Activity box
			int numARKFetchers = node.getNumARKFetchers();

			HTMLNode activityInfobox = nextTableCell.addChild("div", "class", "infobox");
			activityInfobox.addChild("div", "class", "infobox-header", l10n("activityTitle"));
			HTMLNode activityInfoboxContent = activityInfobox.addChild("div", "class", "infobox-content");
			HTMLNode activityList = StatisticsToadlet.drawActivity(activityInfoboxContent, node);
			if (advancedModeEnabled && activityList != null) {
				if (numARKFetchers > 0) {
					activityList.addChild("li", "ARK\u00a0Fetch\u00a0Requests:\u00a0" + numARKFetchers);
				}
				StatisticsToadlet.drawBandwidth(activityList, node, nodeUptimeSeconds);
			}

			nextTableCell = advancedModeEnabled ? overviewTableRow.addChild("td") : overviewTableRow.addChild("td", "class", "last");

			// Peer statistics box
			HTMLNode peerStatsInfobox = nextTableCell.addChild("div", "class", "infobox");
			peerStatsInfobox.addChild("div", "class", "infobox-header", l10nStats("peerStatsTitle"));
			HTMLNode peerStatsContent = peerStatsInfobox.addChild("div", "class", "infobox-content");
			HTMLNode peerStatsList = peerStatsContent.addChild("ul");
			if (numberOfConnected > 0) {
				HTMLNode peerStatsConnectedListItem = peerStatsList.addChild("li").addChild("span");
				peerStatsConnectedListItem.addChild("span", new String[] { "class", "title", "style" }, new String[] { "peer_connected", l10n("connected"), "border-bottom: 1px dotted; cursor: help;" }, l10n("connectedShort"));
				peerStatsConnectedListItem.addChild("span", ":\u00a0" + numberOfConnected);
			}
			if (numberOfRoutingBackedOff > 0) {
				HTMLNode peerStatsRoutingBackedOffListItem = peerStatsList.addChild("li").addChild("span");
				peerStatsRoutingBackedOffListItem.addChild("span", new String[] { "class", "title", "style" }, new String[] { "peer_backed_off", (advancedModeEnabled ? l10n("backedOff") : l10n("busy")), "border-bottom: 1px dotted; cursor: help;" }, advancedModeEnabled ? l10n("backedOffShort") : l10n("busyShort"));
				peerStatsRoutingBackedOffListItem.addChild("span", ":\u00a0" + numberOfRoutingBackedOff);
			}
			if (numberOfTooNew > 0) {
				HTMLNode peerStatsTooNewListItem = peerStatsList.addChild("li").addChild("span");
				peerStatsTooNewListItem.addChild("span", new String[] { "class", "title", "style" }, new String[] { "peer_too_new", l10n("tooNew"), "border-bottom: 1px dotted; cursor: help;" }, l10n("tooNewShort"));
				peerStatsTooNewListItem.addChild("span", ":\u00a0" + numberOfTooNew);
			}
			if (numberOfTooOld > 0) {
				HTMLNode peerStatsTooOldListItem = peerStatsList.addChild("li").addChild("span");
				peerStatsTooOldListItem.addChild("span", new String[] { "class", "title", "style" }, new String[] { "peer_too_old", l10n("tooOld"), "border-bottom: 1px dotted; cursor: help;" }, l10n("tooOldShort"));
				peerStatsTooOldListItem.addChild("span", ":\u00a0" + numberOfTooOld);
			}
			if (numberOfDisconnected > 0) {
				HTMLNode peerStatsDisconnectedListItem = peerStatsList.addChild("li").addChild("span");
				peerStatsDisconnectedListItem.addChild("span", new String[] { "class", "title", "style" }, new String[] { "peer_disconnected", l10n("notConnected"), "border-bottom: 1px dotted; cursor: help;" }, l10n("notConnectedShort"));
				peerStatsDisconnectedListItem.addChild("span", ":\u00a0" + numberOfDisconnected);
			}
			if (numberOfNeverConnected > 0) {
				HTMLNode peerStatsNeverConnectedListItem = peerStatsList.addChild("li").addChild("span");
				peerStatsNeverConnectedListItem.addChild("span", new String[] { "class", "title", "style" }, new String[] { "peer_never_connected", l10n("neverConnected"), "border-bottom: 1px dotted; cursor: help;" }, l10n("neverConnectedShort"));
				peerStatsNeverConnectedListItem.addChild("span", ":\u00a0" + numberOfNeverConnected);
			}
			if (numberOfDisabled > 0) {
				HTMLNode peerStatsDisabledListItem = peerStatsList.addChild("li").addChild("span");
				peerStatsDisabledListItem.addChild("span", new String[] { "class", "title", "style" }, new String[] { "peer_disabled", l10n("disabled"), "border-bottom: 1px dotted; cursor: help;" }, l10n("disabledShort"));
				peerStatsDisabledListItem.addChild("span", ":\u00a0" + numberOfDisabled);
			}
			if (numberOfBursting > 0) {
				HTMLNode peerStatsBurstingListItem = peerStatsList.addChild("li").addChild("span");
				peerStatsBurstingListItem.addChild("span", new String[] { "class", "title", "style" }, new String[] { "peer_bursting", l10n("bursting"), "border-bottom: 1px dotted; cursor: help;" }, l10n("burstingShort"));
				peerStatsBurstingListItem.addChild("span", ":\u00a0" + numberOfBursting);
			}
			if (numberOfListening > 0) {
				HTMLNode peerStatsListeningListItem = peerStatsList.addChild("li").addChild("span");
				peerStatsListeningListItem.addChild("span", new String[] { "class", "title", "style" }, new String[] { "peer_listening", l10n("listening"), "border-bottom: 1px dotted; cursor: help;" }, l10n("listeningShort"));
				peerStatsListeningListItem.addChild("span", ":\u00a0" + numberOfListening);
			}
			if (numberOfListenOnly > 0) {
				HTMLNode peerStatsListenOnlyListItem = peerStatsList.addChild("li").addChild("span");
				peerStatsListenOnlyListItem.addChild("span", new String[] { "class", "title", "style" }, new String[] { "peer_listen_only", l10n("listenOnly"), "border-bottom: 1px dotted; cursor: help;" }, l10n("listenOnlyShort"));
				peerStatsListenOnlyListItem.addChild("span", ":\u00a0" + numberOfListenOnly);
			}

			// Peer routing backoff reason box
			if(advancedModeEnabled) {
				nextTableCell = overviewTableRow.addChild("td", "class", "last");
				HTMLNode backoffReasonInfobox = nextTableCell.addChild("div", "class", "infobox");
				backoffReasonInfobox.addChild("div", "class", "infobox-header", "Peer backoff reasons");
				HTMLNode backoffReasonContent = backoffReasonInfobox.addChild("div", "class", "infobox-content");
				String [] routingBackoffReasons = peers.getPeerNodeRoutingBackoffReasons();
				if(routingBackoffReasons.length == 0) {
					backoffReasonContent.addChild("#", "Good, your node is not backed off from any peers!");
				} else {
					HTMLNode reasonList = backoffReasonContent.addChild("ul");
					for(int i=0;i<routingBackoffReasons.length;i++) {
						int reasonCount = peers.getPeerNodeRoutingBackoffReasonSize(routingBackoffReasons[i]);
						if(reasonCount > 0) {
							reasonList.addChild("li", routingBackoffReasons[i] + '\u00a0' + reasonCount);
						}
					}
				}
			}
			// END OVERVIEW TABLE

			// BEGIN PEER TABLE
			if(fProxyJavascriptEnabled) {
				StringBuffer jsBuf = new StringBuffer();
				// FIXME: There's probably some icky Javascript in here (this is the first thing that worked for me); feel free to fix up to Javascript guru standards
				jsBuf.append( "  function peerNoteChange() {\n" );
				jsBuf.append( "    var theobj = document.getElementById( \"action\" );\n" );
				jsBuf.append( "    var length = theobj.options.length;\n" );
				jsBuf.append( "    for (var i = 0; i < length; i++) {\n" );
				jsBuf.append( "      if(theobj.options[i] == \"update_notes\") {\n" );
				jsBuf.append( "        theobj.options[i].select = true;\n" );
				jsBuf.append( "      } else {\n" );
				jsBuf.append( "        theobj.options[i].select = false;\n" );
				jsBuf.append( "      }\n" );
				jsBuf.append( "    }\n" );
				jsBuf.append( "    theobj.value=\"update_notes\";\n" );
				//jsBuf.append( "    document.getElementById( \"peersForm\" ).submit();\n" );
				jsBuf.append( "    document.getElementById( \"peersForm\" ).doAction.click();\n" );
				jsBuf.append( "  }\n" );
				jsBuf.append( "  function peerNoteBlur() {\n" );
				jsBuf.append( "    var theobj = document.getElementById( \"action\" );\n" );
				jsBuf.append( "    var length = theobj.options.length;\n" );
				jsBuf.append( "    for (var i = 0; i < length; i++) {\n" );
				jsBuf.append( "      if(theobj.options[i] == \"update_notes\") {\n" );
				jsBuf.append( "        theobj.options[i].select = true;\n" );
				jsBuf.append( "      } else {\n" );
				jsBuf.append( "        theobj.options[i].select = false;\n" );
				jsBuf.append( "      }\n" );
				jsBuf.append( "    }\n" );
				jsBuf.append( "    theobj.value=\"update_notes\";\n" );
				jsBuf.append( "  }\n" );
				contentNode.addChild("script", "type", "text/javascript").addChild("%", jsBuf.toString());
			}
			HTMLNode peerTableInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
			HTMLNode peerTableInfoboxHeader = peerTableInfobox.addChild("div", "class", "infobox-header");
			peerTableInfoboxHeader.addChild("#", l10n("myFriends"));
			if (advancedModeEnabled) {
				if (!path.endsWith("displaymessagetypes.html")) {
					peerTableInfoboxHeader.addChild("#", " ");
					peerTableInfoboxHeader.addChild("a", "href", "displaymessagetypes.html", "(more detailed)");
				}
			}
			HTMLNode peerTableInfoboxContent = peerTableInfobox.addChild("div", "class", "infobox-content");

			if (peerNodeStatuses.length == 0) {
				L10n.addL10nSubstitution(peerTableInfoboxContent, "DarknetConnectionsToadlet.noPeersWithHomepageLink", 
						new String[] { "link", "/link" }, new String[] { "<a href=\"/\">", "</a>" });
			} else {
				HTMLNode peerForm = ctx.addFormChild(peerTableInfoboxContent, ".", "peersForm");
				HTMLNode peerTable = peerForm.addChild("table", "class", "darknet_connections");
				HTMLNode peerTableHeaderRow = peerTable.addChild("tr");
				peerTableHeaderRow.addChild("th");
				peerTableHeaderRow.addChild("th").addChild("a", "href", sortString(isReversed, "status")).addChild("#", l10n("statusTitle"));
				peerTableHeaderRow.addChild("th").addChild("a", "href", sortString(isReversed, "name")).addChild("span", new String[] { "title", "style" }, new String[] { l10n("nameClickToMessage"), "border-bottom: 1px dotted; cursor: help;" }, l10n("nameTitle"));
				if (advancedModeEnabled) {
					peerTableHeaderRow.addChild("th").addChild("a", "href", sortString(isReversed, "address")).addChild("span", new String[] { "title", "style" }, new String[] { l10n("ipAddress"), "border-bottom: 1px dotted; cursor: help;" }, l10n("ipAddressTitle"));
				}
				peerTableHeaderRow.addChild("th").addChild("a", "href", sortString(isReversed, "version")).addChild("#", l10n("versionTitle"));
				if (advancedModeEnabled) {
					peerTableHeaderRow.addChild("th").addChild("a", "href", sortString(isReversed, "location")).addChild("#", "Location");
					peerTableHeaderRow.addChild("th").addChild("span", new String[] { "title", "style" }, new String[] { "Other node busy? Display: Percentage of time the node is overloaded, Current wait time remaining (0=not overloaded)/total/last overload reason", "border-bottom: 1px dotted; cursor: help;" }, "Backoff");

					peerTableHeaderRow.addChild("th").addChild("span", new String[] { "title", "style" }, new String[] { "Probability of the node rejecting a request due to overload or causing a timeout.", "border-bottom: 1px dotted; cursor: help;" }, "Overload Probability");
				}
				peerTableHeaderRow.addChild("th").addChild("span", new String[] { "title", "style" }, new String[] { l10n("idleTime"), "border-bottom: 1px dotted; cursor: help;" }, l10n("idleTimeTitle"));
				peerTableHeaderRow.addChild("th").addChild("a", "href", sortString(isReversed, "privnote")).addChild("span", new String[] { "title", "style" }, new String[] { l10n("privateNote"), "border-bottom: 1px dotted; cursor: help;" }, l10n("privateNoteTitle"));

				if(advancedModeEnabled) {
					peerTableHeaderRow.addChild("th", "%\u00a0Time Routable");
					peerTableHeaderRow.addChild("th", "Total\u00a0Traffic\u00a0(in/out)");
					peerTableHeaderRow.addChild("th", "Congestion\u00a0Control");
				}
				
				for (int peerIndex = 0, peerCount = peerNodeStatuses.length; peerIndex < peerCount; peerIndex++) {
					PeerNodeStatus peerNodeStatus = peerNodeStatuses[peerIndex];
					HTMLNode peerRow = peerTable.addChild("tr");

					// check box column
					peerRow.addChild("td", "class", "peer-marker").addChild("input", new String[] { "type", "name" }, new String[] { "checkbox", "node_" + peerNodeStatus.hashCode() });

					// status column
					String statusString = peerNodeStatus.getStatusName();
					if (!advancedModeEnabled && (peerNodeStatus.getStatusValue() == PeerManager.PEER_NODE_STATUS_ROUTING_BACKED_OFF)) {
						statusString = "BUSY";
					}
					peerRow.addChild("td", "class", "peer-status").addChild("span", "class", peerNodeStatus.getStatusCSSName(), statusString + (peerNodeStatus.isFetchingARK() ? "*" : ""));

					// name column
					peerRow.addChild("td", "class", "peer-name").addChild("a", "href", "/send_n2ntm/?peernode_hashcode=" + peerNodeStatus.hashCode(), peerNodeStatus.getName());

					// address column
					if (advancedModeEnabled) {
						String pingTime = "";
						if (peerNodeStatus.isConnected()) {
							pingTime = " (" + (int) peerNodeStatus.getAveragePingTime() + "ms)";
						}
						peerRow.addChild("td", "class", "peer-address").addChild("#", ((peerNodeStatus.getPeerAddress() != null) ? (peerNodeStatus.getPeerAddress() + ':' + peerNodeStatus.getPeerPort()) : (l10n("unknownAddress"))) + pingTime);
					}

					// version column
					if (peerNodeStatus.getStatusValue() != PeerManager.PEER_NODE_STATUS_NEVER_CONNECTED && (peerNodeStatus.isPublicInvalidVersion() || peerNodeStatus.isPublicReverseInvalidVersion())) {  // Don't draw attention to a version problem if NEVER CONNECTED
						peerRow.addChild("td", "class", "peer-version").addChild("span", "class", "peer_version_problem", peerNodeStatus.getSimpleVersion());
					} else {
						peerRow.addChild("td", "class", "peer-version").addChild("#", peerNodeStatus.getSimpleVersion());
					}

					// location column
					if (advancedModeEnabled) {
						peerRow.addChild("td", "class", "peer-location", String.valueOf(peerNodeStatus.getLocation()));
					}

					if (advancedModeEnabled) {
						// backoff column
						HTMLNode backoffCell = peerRow.addChild("td", "class", "peer-backoff");
						backoffCell.addChild("#", fix1.format(peerNodeStatus.getBackedOffPercent()));
						int backoff = (int) (Math.max(peerNodeStatus.getRoutingBackedOffUntil() - now, 0));
						// Don't list the backoff as zero before it's actually zero
						if ((backoff > 0) && (backoff < 1000)) {
							backoff = 1000;
						}
						backoffCell.addChild("#", ' ' + String.valueOf(backoff / 1000) + '/' + String.valueOf(peerNodeStatus.getRoutingBackoffLength() / 1000));
						backoffCell.addChild("#", (peerNodeStatus.getLastBackoffReason() == null) ? "" : ('/' + (peerNodeStatus.getLastBackoffReason())));

						// overload probability column
						HTMLNode pRejectCell = peerRow.addChild("td", "class", "peer-backoff"); // FIXME
						pRejectCell.addChild("#", fix1.format(peerNodeStatus.getPReject()));
					}

					// idle column
					long idle = peerNodeStatus.getTimeLastRoutable();
					if (peerNodeStatus.isRoutable()) {
						idle = peerNodeStatus.getTimeLastConnectionCompleted();
					} else if (peerNodeStatus.getStatusValue() == PeerManager.PEER_NODE_STATUS_NEVER_CONNECTED) {
						idle = peerNodeStatus.getPeerAddedTime();
					}
					if(!peerNodeStatus.isConnected() && (now - idle) > (2 * 7 * 24 * 60 * 60 * (long) 1000)) { // 2 weeks
						peerRow.addChild("td", "class", "peer-idle").addChild("span", "class", "peer_idle_old", idleToString(now, idle));
					} else {
						peerRow.addChild("td", "class", "peer-idle", idleToString(now, idle));
					}

					// private darknet node comment note column
					if(fProxyJavascriptEnabled) {
						peerRow.addChild("td", "class", "peer-private-darknet-comment-note").addChild("input", new String[] { "type", "name", "size", "maxlength", "onBlur", "onChange", "value" }, new String[] { "text", "peerPrivateNote_" + peerNodeStatus.hashCode(), "16", "250", "peerNoteBlur();", "peerNoteChange();", peerNodeStatus.getPrivateDarknetCommentNote() });
					} else {
						peerRow.addChild("td", "class", "peer-private-darknet-comment-note").addChild("input", new String[] { "type", "name", "size", "maxlength", "value" }, new String[] { "text", "peerPrivateNote_" + peerNodeStatus.hashCode(), "16", "250", peerNodeStatus.getPrivateDarknetCommentNote() });
					}

					if(advancedModeEnabled) {
						// percent of time connected column
						peerRow.addChild("td", "class", "peer-idle" /* FIXME */).addChild("#", fix1.format(peerNodeStatus.getPercentTimeRoutableConnection()));
						// total traffic column
						peerRow.addChild("td", "class", "peer-idle" /* FIXME */).addChild("#", SizeUtil.formatSize(peerNodeStatus.getTotalInputBytes())+" / "+SizeUtil.formatSize(peerNodeStatus.getTotalOutputBytes()));
						// congestion control
						PacketThrottle t = peerNodeStatus.getThrottle();
						String val;
						if(t == null)
							val = "none";
						else
							val = (int)((1000.0 / t.getDelay()) * 1024.0)+"B/sec delay "+
								t.getDelay()+"ms (RTT "+t.getRoundTripTime()+"ms window "+t.getWindowSize();
						peerRow.addChild("td", "class", "peer-idle" /* FIXME */).addChild("#", val);
					}
					
					if (path.endsWith("displaymessagetypes.html")) {
						HTMLNode messageCountRow = peerTable.addChild("tr", "class", "message-status");
						messageCountRow.addChild("td", "colspan", "2");
						HTMLNode messageCountCell = messageCountRow.addChild("td", "colspan", String.valueOf(advancedModeEnabled ? 9 : 5));  // = total table row width - 2 from above colspan
						HTMLNode messageCountTable = messageCountCell.addChild("table", "class", "message-count");
						HTMLNode countHeaderRow = messageCountTable.addChild("tr");
						countHeaderRow.addChild("th", "Message");
						countHeaderRow.addChild("th", "Incoming");
						countHeaderRow.addChild("th", "Outgoing");
						List messageNames = new ArrayList();
						Map messageCounts = new HashMap();
						for (Iterator incomingMessages = peerNodeStatus.getLocalMessagesReceived().keySet().iterator(); incomingMessages.hasNext(); ) {
							String messageName = (String) incomingMessages.next();
							messageNames.add(messageName);
							Long messageCount = (Long) peerNodeStatus.getLocalMessagesReceived().get(messageName);
							messageCounts.put(messageName, new Long[] { messageCount, new Long(0) });
						}
						for (Iterator outgoingMessages = peerNodeStatus.getLocalMessagesSent().keySet().iterator(); outgoingMessages.hasNext(); ) {
							String messageName = (String) outgoingMessages.next();
							if (!messageNames.contains(messageName)) {
								messageNames.add(messageName);
							}
							Long messageCount = (Long) peerNodeStatus.getLocalMessagesSent().get(messageName);
							Long[] existingCounts = (Long[]) messageCounts.get(messageName);
							if (existingCounts == null) {
								messageCounts.put(messageName, new Long[] { new Long(0), messageCount });
							} else {
								existingCounts[1] = messageCount;
							}
						}
						Collections.sort(messageNames, new Comparator() {
							public int compare(Object first, Object second) {
								return ((String) first).compareToIgnoreCase((String) second);
							}
						});
						for (Iterator messageNamesIterator = messageNames.iterator(); messageNamesIterator.hasNext(); ) {
							String messageName = (String) messageNamesIterator.next();
							Long[] messageCount = (Long[]) messageCounts.get(messageName);
							HTMLNode messageRow = messageCountTable.addChild("tr");
							messageRow.addChild("td", messageName);
							messageRow.addChild("td", "class", "right-align", String.valueOf(messageCount[0]));
							messageRow.addChild("td", "class", "right-align", String.valueOf(messageCount[1]));
						}
					}
				}

				HTMLNode actionSelect = peerForm.addChild("select", new String[] { "id", "name" }, new String[] { "action", "action" });
				actionSelect.addChild("option", "value", "", l10n("selectAction"));
				actionSelect.addChild("option", "value", "send_n2ntm", l10n("sendMessageToPeers"));
				actionSelect.addChild("option", "value", "update_notes", l10n("updateChangedPrivnotes"));
				if(advancedModeEnabled) {
					actionSelect.addChild("option", "value", "enable", "Enable selected peers");
					actionSelect.addChild("option", "value", "disable", "Disable selected peers");
					actionSelect.addChild("option", "value", "set_burst_only", "On selected peers, set BurstOnly (only set this if you have a static IP and are not NATed and neither is the peer)");
					actionSelect.addChild("option", "value", "clear_burst_only", "On selected peers, clear BurstOnly");
					actionSelect.addChild("option", "value", "set_listen_only", "On selected peers, set ListenOnly (not recommended)");
					actionSelect.addChild("option", "value", "clear_listen_only", "On selected peers, clear ListenOnly");
					actionSelect.addChild("option", "value", "set_allow_local", "On selected peers, set allowLocalAddresses (useful if you are connecting to another node on the same LAN)");
					actionSelect.addChild("option", "value", "clear_allow_local", "On selected peers, clear allowLocalAddresses");
					actionSelect.addChild("option", "value", "set_ignore_source_port", "On selected peers, set ignoreSourcePort (try this if behind an evil corporate firewall; otherwise not recommended)");
					actionSelect.addChild("option", "value", "clear_ignore_source_port", "On selected peers, clear ignoreSourcePort");
				}
				actionSelect.addChild("option", "value", "", l10n("separator"));
				actionSelect.addChild("option", "value", "remove", l10n("removePeers"));
				peerForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "doAction", l10n("go") });
				
			}
			// END PEER TABLE
		}

		// BEGIN PEER ADDITION BOX
		HTMLNode peerAdditionInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		peerAdditionInfobox.addChild("div", "class", "infobox-header", l10n("addPeerTitle"));
		HTMLNode peerAdditionContent = peerAdditionInfobox.addChild("div", "class", "infobox-content");
		HTMLNode peerAdditionForm = ctx.addFormChild(peerAdditionContent, ".", "addPeerForm");
		peerAdditionForm.addChild("#", l10n("pasteReference"));
		peerAdditionForm.addChild("br");
		peerAdditionForm.addChild("textarea", new String[] { "id", "name", "rows", "cols" }, new String[] { "reftext", "ref", "8", "74" });
		peerAdditionForm.addChild("br");
		peerAdditionForm.addChild("#", (l10n("urlReference") + ' '));
		peerAdditionForm.addChild("input", new String[] { "id", "type", "name" }, new String[] { "refurl", "text", "url" });
		peerAdditionForm.addChild("br");
		peerAdditionForm.addChild("#", (l10n("fileReference") + ' '));
		peerAdditionForm.addChild("input", new String[] { "id", "type", "name" }, new String[] { "reffile", "file", "reffile" });
		peerAdditionForm.addChild("br");
		peerAdditionForm.addChild("#", (l10n("enterDescription") + ' '));
		peerAdditionForm.addChild("input", new String[] { "id", "type", "name", "size", "maxlength", "value" }, new String[] { "peerPrivateNote", "text", "peerPrivateNote", "16", "250", "" });
		peerAdditionForm.addChild("br");
		peerAdditionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "add", l10n("add") });
		
		// our reference
		HTMLNode referenceInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		HTMLNode headerReferenceInfobox = referenceInfobox.addChild("div", "class", "infobox-header");
		// FIXME better way to deal with this sort of thing???
		L10n.addL10nSubstitution(headerReferenceInfobox, "DarknetConnectionsToadlet.myReferenceHeader",
				new String[] { "linkref", "/linkref", "linktext", "/linktext" },
				new String[] { "<a href=\"myref.fref\">", "</a>", "<a href=\"myref.txt\">", "</a>" });
		HTMLNode warningSentence = headerReferenceInfobox.addChild("pre");
		L10n.addL10nSubstitution(warningSentence, "DarknetConnectionsToadlet.referenceCopyWarning",
				new String[] { "bold", "/bold" },
				new String[] { "<b>", "</b>" });
		referenceInfobox.addChild("div", "class", "infobox-content").addChild("pre", "id", "reference", node.exportPublicFieldSet().toString() + '\n');
		
		// our ports
		HTMLNode portInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		portInfobox.addChild("div", "class", "infobox-header", l10n("nodePortsTitle"));
		HTMLNode portInfoboxContent = portInfobox.addChild("div", "class", "infobox-content");
		HTMLNode portInfoList = portInfoboxContent.addChild("ul");
		SimpleFieldSet fproxyConfig = node.config.get("fproxy").exportFieldSet(true);
		SimpleFieldSet fcpConfig = node.config.get("fcp").exportFieldSet(true);
		SimpleFieldSet tmciConfig = node.config.get("console").exportFieldSet(true);
		portInfoList.addChild("li", L10n.getString("DarknetConnectionsToadlet.fnpPort", new String[] { "port" }, new String[] { Integer.toString(node.getFNPPort()) }));
		try {
			if(fproxyConfig.getBoolean("enabled", false)) {
				portInfoList.addChild("li", L10n.getString("DarknetConnectionsToadlet.fproxyPort", new String[] { "port" }, new String[] { Integer.toString(fproxyConfig.getInt("port")) }));
			} else {
				portInfoList.addChild("li", l10n("fproxyDisabled"));
			}
			if(fcpConfig.getBoolean("enabled", false)) {
				portInfoList.addChild("li", L10n.getString("DarknetConnectionsToadlet.fcpPort", new String[] { "port" }, new String[] { Integer.toString(fcpConfig.getInt("port")) }));
			} else {
				portInfoList.addChild("li", l10n("fcpDisabled"));
			}
			if(tmciConfig.getBoolean("enabled", false)) {
				portInfoList.addChild("li", L10n.getString("DarknetConnectionsToadlet.tmciPort", new String[] { "port" }, new String[] { Integer.toString(tmciConfig.getInt("port")) }));
			} else {
				portInfoList.addChild("li", l10n("tmciDisabled"));
			}
		} catch (FSParseException e) {
			// ignore
		}
		
		this.writeReply(ctx, 200, "text/html", "OK", pageNode.generate());
	}
	
	private static String l10n(String string) {
		return L10n.getString("DarknetConnectionsToadlet."+string);
	}
	
	private static String l10nStats(String string) {
		return L10n.getString("StatisticsToadlet."+string);
	}

	private String sortString(boolean isReversed, String type) {
		return (isReversed ? ("?sortBy="+type) : ("?sortBy="+type+"&reversed"));
	}

	public void handlePost(URI uri, final HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		
		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, "Unauthorized", L10n.getString("Toadlet.unauthorized"));
			return;
		}
		
		String pass = request.getPartAsString("formPassword", 32);
		if((pass == null) || !pass.equals(core.formPassword)) {
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/friends/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			if(logMINOR) Logger.minor(this, "No password ("+pass+" should be "+core.formPassword+ ')');
			return;
		}
		
		if (request.isPartSet("add")) {
			// add a new node
			String urltext = request.getPartAsString("url", 100);
			urltext = urltext.trim();
			String reftext = request.getPartAsString("ref", 2000);
			reftext = reftext.trim();
			if (reftext.length() < 200) {
				reftext = request.getPartAsString("reffile", 2000);
				reftext = reftext.trim();
			}
			String privateComment = request.getPartAsString("peerPrivateNote", 250).trim();
			
			StringBuffer ref = new StringBuffer(1024);
			if (urltext.length() > 0) {
				// fetch reference from a URL
				BufferedReader in = null;
				try {
					URL url = new URL(urltext);
					URLConnection uc = url.openConnection();
					in = new BufferedReader(new InputStreamReader(uc.getInputStream()));
					String line;
					while ( (line = in.readLine()) != null) {
						ref.append( line ).append('\n');
					}
				} catch (IOException e) {
					this.sendErrorPage(ctx, 200, l10n("failedToAddNodeTitle"), L10n.getString("DarknetConnectionsToadlet.cantFetchNoderefURL", new String[] { "url" }, new String[] { urltext }));
					return;
				} finally {
					if( in != null ){
						in.close();
					}
				}
			} else if (reftext.length() > 0) {
				// read from post data or file upload
				// this slightly scary looking regexp chops any extra characters off the beginning or ends of lines and removes extra line breaks
				ref = new StringBuffer(reftext.replaceAll(".*?((?:[\\w,\\.]+\\=[^\r\n]+?)|(?:End))[ \\t]*(?:\\r?\\n)+", "$1\n"));
			} else {
				this.sendErrorPage(ctx, 200, l10n("failedToAddNodeTitle"), l10n("noRefOrURL"));
				request.freeParts();
				return;
			}
			ref = new StringBuffer(ref.toString().trim());

			request.freeParts();
			// we have a node reference in ref
			SimpleFieldSet fs;
			
			try {
				fs = new SimpleFieldSet(ref.toString(), false, true);
				if(!fs.getEndMarker().endsWith("End")) {
					sendErrorPage(ctx, 200, l10n("failedToAddNodeTitle"),
							L10n.getString("DarknetConnectionsToadlet.cantParseWrongEnding", new String[] { "end" }, new String[] { fs.getEndMarker() }));
					return;
				}
				fs.setEndMarker("End"); // It's always End ; the regex above doesn't always grok this
			} catch (IOException e) {
				this.sendErrorPage(ctx, 200, l10n("failedToAddNodeTitle"), 
						L10n.getString("DarknetConnectionsToadlet.cantParseTryAgain", new String[] { "error" }, new String[] { e.toString() }));
				return;
			} catch (Throwable t) {
				this.sendErrorPage(ctx, l10n("failedToAddNodeInternalErrorTitle"), l10n("failedToAddNodeInternalError"), t);
				return;
			}
			PeerNode pn;
			try {
				pn = new PeerNode(fs, node, node.peers, false);
				pn.setPrivateDarknetCommentNote(privateComment);
			} catch (FSParseException e1) {
				this.sendErrorPage(ctx, 200, l10n("failedToAddNodeTitle"),
						L10n.getString("DarknetConnectionsToadlet.cantParseTryAgain", new String[] { "error" }, new String[] { e1.toString() }));
				return;
			} catch (PeerParseException e1) {
				this.sendErrorPage(ctx, 200, l10n("failedToAddNodeTitle"), 
						L10n.getString("DarknetConnectionsToadlet.cantParseTryAgain", new String[] { "error" }, new String[] { e1.toString() }));
				return;
			} catch (ReferenceSignatureVerificationException e1){
				HTMLNode node = new HTMLNode("div");
				node.addChild("#", L10n.getString("DarknetConnectionsToadlet.invalidSignature", new String[] { "error" }, new String[] { e1.toString() }));
				node.addChild("br");
				this.sendErrorPage(ctx, 200, l10n("failedToAddNodeTitle"), node);
				return;
			} catch (Throwable t) {
				this.sendErrorPage(ctx, l10n("failedToAddNodeInternalErrorTitle"), l10n("failedToAddNodeInternalError"), t);
				return;
			}
			if(pn.getIdentityHash()==node.getIdentityHash()) {
				this.sendErrorPage(ctx, 200, l10n("failedToAddNodeTitle"), l10n("triedToAddSelf"));
				return;
			}
			if(!this.node.addDarknetConnection(pn)) {
				this.sendErrorPage(ctx, 200, l10n("failedToAddNodeTitle"), l10n("alreadyInReferences"));
				return;
			}
			
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/friends/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		} else if (request.isPartSet("doAction") && request.getPartAsString("action",25).equals("send_n2ntm")) {
			HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("sendMessageTitle"), ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			PeerNode[] peerNodes = node.getDarknetConnections();
			HashMap peers = new HashMap();
			for(int i = 0; i < peerNodes.length; i++) {
				if (request.isPartSet("node_"+peerNodes[i].hashCode())) {
					PeerNode pn = peerNodes[i];
					String peer_name = pn.getName();
					String peer_hash = "" + pn.hashCode();
					if(!peers.containsKey(peer_hash)) {
						peers.put(peer_hash, peer_name);
					}
				}
			}
			N2NTMToadlet.createN2NTMSendForm( pageNode, contentNode, ctx, peers);
			this.writeReply(ctx, 200, "text/html", "OK", pageNode.generate());
			return;
		} else if (request.isPartSet("doAction") && request.getPartAsString("action",25).equals("update_notes")) {
			//int hashcode = Integer.decode(request.getParam("node")).intValue();
			
			PeerNode[] peerNodes = node.getDarknetConnections();
			for(int i = 0; i < peerNodes.length; i++) {
				if (request.isPartSet("peerPrivateNote_"+peerNodes[i].hashCode())) {
					if(!request.getPartAsString("peerPrivateNote_"+peerNodes[i].hashCode(),250).equals(peerNodes[i].getPrivateDarknetCommentNote())) {
						peerNodes[i].setPrivateDarknetCommentNote(request.getPartAsString("peerPrivateNote_"+peerNodes[i].hashCode(),250));
					}
				}
			}
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/friends/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		} else if (request.isPartSet("doAction") && request.getPartAsString("action",25).equals("enable")) {
			//int hashcode = Integer.decode(request.getParam("node")).intValue();
			
			PeerNode[] peerNodes = node.getDarknetConnections();
			for(int i = 0; i < peerNodes.length; i++) {
				if (request.isPartSet("node_"+peerNodes[i].hashCode())) {
					peerNodes[i].enablePeer();
				}
			}
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/friends/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		} else if (request.isPartSet("doAction") && request.getPartAsString("action",25).equals("disable")) {
			//int hashcode = Integer.decode(request.getParam("node")).intValue();
			
			PeerNode[] peerNodes = node.getDarknetConnections();
			for(int i = 0; i < peerNodes.length; i++) {
				if (request.isPartSet("node_"+peerNodes[i].hashCode())) {
					peerNodes[i].disablePeer();
				}
			}
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/friends/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		} else if (request.isPartSet("doAction") && request.getPartAsString("action",25).equals("set_burst_only")) {
			//int hashcode = Integer.decode(request.getParam("node")).intValue();
			
			PeerNode[] peerNodes = node.getDarknetConnections();
			for(int i = 0; i < peerNodes.length; i++) {
				if (request.isPartSet("node_"+peerNodes[i].hashCode())) {
					peerNodes[i].setBurstOnly(true);
				}
			}
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/friends/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		} else if (request.isPartSet("doAction") && request.getPartAsString("action",25).equals("clear_burst_only")) {
			//int hashcode = Integer.decode(request.getParam("node")).intValue();
			
			PeerNode[] peerNodes = node.getDarknetConnections();
			for(int i = 0; i < peerNodes.length; i++) {
				if (request.isPartSet("node_"+peerNodes[i].hashCode())) {
					peerNodes[i].setBurstOnly(false);
				}
			}
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/friends/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		} else if (request.isPartSet("doAction") && request.getPartAsString("action",25).equals("set_ignore_source_port")) {
			//int hashcode = Integer.decode(request.getParam("node")).intValue();
			
			PeerNode[] peerNodes = node.getDarknetConnections();
			for(int i = 0; i < peerNodes.length; i++) {
				if (request.isPartSet("node_"+peerNodes[i].hashCode())) {
					peerNodes[i].setIgnoreSourcePort(true);
				}
			}
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/friends/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		} else if (request.isPartSet("doAction") && request.getPartAsString("action",25).equals("clear_ignore_source_port")) {
			//int hashcode = Integer.decode(request.getParam("node")).intValue();
			
			PeerNode[] peerNodes = node.getDarknetConnections();
			for(int i = 0; i < peerNodes.length; i++) {
				if (request.isPartSet("node_"+peerNodes[i].hashCode())) {
					peerNodes[i].setIgnoreSourcePort(false);
				}
			}
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/friends/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		} else if (request.isPartSet("doAction") && request.getPartAsString("action",25).equals("set_listen_only")) {
			//int hashcode = Integer.decode(request.getParam("node")).intValue();
			
			PeerNode[] peerNodes = node.getDarknetConnections();
			for(int i = 0; i < peerNodes.length; i++) {
				if (request.isPartSet("node_"+peerNodes[i].hashCode())) {
					peerNodes[i].setListenOnly(true);
				}
			}
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/friends/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		} else if (request.isPartSet("doAction") && request.getPartAsString("action",25).equals("clear_listen_only")) {
			//int hashcode = Integer.decode(request.getParam("node")).intValue();
			
			PeerNode[] peerNodes = node.getDarknetConnections();
			for(int i = 0; i < peerNodes.length; i++) {
				if (request.isPartSet("node_"+peerNodes[i].hashCode())) {
					peerNodes[i].setListenOnly(false);
				}
			}
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/friends/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		} else if (request.isPartSet("doAction") && request.getPartAsString("action",25).equals("set_allow_local")) {
			//int hashcode = Integer.decode(request.getParam("node")).intValue();
			
			PeerNode[] peerNodes = node.getDarknetConnections();
			for(int i = 0; i < peerNodes.length; i++) {
				if (request.isPartSet("node_"+peerNodes[i].hashCode())) {
					peerNodes[i].setAllowLocalAddresses(true);
				}
			}
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/friends/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		} else if (request.isPartSet("doAction") && request.getPartAsString("action",25).equals("clear_allow_local")) {
			//int hashcode = Integer.decode(request.getParam("node")).intValue();
			
			PeerNode[] peerNodes = node.getDarknetConnections();
			for(int i = 0; i < peerNodes.length; i++) {
				if (request.isPartSet("node_"+peerNodes[i].hashCode())) {
					peerNodes[i].setAllowLocalAddresses(false);
				}
			}
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/friends/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		} else if (request.isPartSet("remove") || (request.isPartSet("doAction") && request.getPartAsString("action",25).equals("remove"))) {			
			if(logMINOR) Logger.minor(this, "Remove node");
			
			PeerNode[] peerNodes = node.getDarknetConnections();
			for(int i = 0; i < peerNodes.length; i++) {
				if (request.isPartSet("node_"+peerNodes[i].hashCode())) {	
					if((peerNodes[i].timeLastConnectionCompleted() < (System.currentTimeMillis() - 1000*60*60*24*7) /* one week */) ||  (peerNodes[i].peerNodeStatus == PeerManager.PEER_NODE_STATUS_NEVER_CONNECTED) || request.isPartSet("forceit")){
						this.node.removeDarknetConnection(peerNodes[i]);
						if(logMINOR) Logger.minor(this, "Removed node: node_"+peerNodes[i].hashCode());
					}else{
						if(logMINOR) Logger.minor(this, "Refusing to remove : node_"+peerNodes[i].hashCode()+" (trying to prevent network churn) : let's display the warning message.");
						HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("Please confirm"), ctx);
						HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
						HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-warning", l10n("confirmRemoveNodeWarningTitle")));
						HTMLNode content = ctx.getPageMaker().getContentNode(infobox);
						content.addChild("p").addChild("#",
								L10n.getString("DarknetConnectionsToadlet.confirmRemoveNode", new String[] { "name" }, new String[] { peerNodes[i].getName() }));
						HTMLNode removeForm = ctx.addFormChild(content, "/friends/", "removeConfirmForm");
						removeForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "node_"+peerNodes[i].hashCode(), "remove" });
						removeForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", L10n.getString("Toadlet.cancel") });
						removeForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "remove", l10n("remove") });
						removeForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "forceit", l10n("forceRemove") });

						writeReply(ctx, 200, "text/html", "OK", pageNode.generate());
						return; // FIXME: maybe it breaks multi-node removing
					}				
				} else {
					if(logMINOR) Logger.minor(this, "Part not set: node_"+peerNodes[i].hashCode());
				}
			}
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/friends/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		} else if (request.isPartSet("acceptTransfer")) {
			// FIXME this is ugly, should probably move both this code and the PeerNode code somewhere.
			PeerNode[] peerNodes = node.getDarknetConnections();
			for(int i = 0; i < peerNodes.length; i++) {
				if (request.isPartSet("node_"+peerNodes[i].hashCode())) {
					long id = Long.parseLong(request.getPartAsString("id", 32)); // FIXME handle NumberFormatException
					peerNodes[i].acceptTransfer(id);
					break;
				}
			}
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/friends/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		} else if (request.isPartSet("rejectTransfer")) {
			// FIXME this is ugly, should probably move both this code and the PeerNode code somewhere.
			PeerNode[] peerNodes = node.getDarknetConnections();
			for(int i = 0; i < peerNodes.length; i++) {
				if (request.isPartSet("node_"+peerNodes[i].hashCode())) {
					long id = Long.parseLong(request.getPartAsString("id", 32)); // FIXME handle NumberFormatException
					peerNodes[i].rejectTransfer(id);
					break;
				}
			}
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/friends/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		} else {
			this.handleGet(uri, new HTTPRequestImpl(uri), ctx);
		}
	}
	
	private String idleToString(long now, long idle) {
		if (idle <= 0) {
			return " ";
		}
		long idleMilliseconds = now - idle;
		return TimeUtil.formatTime(idleMilliseconds);
	}
}
