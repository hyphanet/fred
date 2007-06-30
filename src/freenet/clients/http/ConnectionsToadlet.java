package freenet.clients.http;

import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
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
import freenet.io.xfer.PacketThrottle;
import freenet.l10n.L10n;
import freenet.node.DarknetPeerNodeStatus;
import freenet.node.FSParseException;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.NodeStats;
import freenet.node.PeerManager;
import freenet.node.PeerNodeStatus;
import freenet.node.Version;
import freenet.support.HTMLNode;
import freenet.support.MultiValueTable;
import freenet.support.SimpleFieldSet;
import freenet.support.SizeUtil;
import freenet.support.TimeUtil;
import freenet.support.api.HTTPRequest;

public abstract class ConnectionsToadlet extends Toadlet {

	protected class ComparatorByStatus implements Comparator {
		
		protected final String sortBy;
		protected final boolean reversed;
		
		ComparatorByStatus(String sortBy, boolean reversed) {
			this.sortBy = sortBy;
			this.reversed = reversed;
		}
		
		public int compare(Object first, Object second) {
			int result = 0;
			boolean isSet = true;
			PeerNodeStatus firstNode = (DarknetPeerNodeStatus) first;
			PeerNodeStatus secondNode = (DarknetPeerNodeStatus) second;
			
			if(sortBy != null){
				result = customCompare(firstNode, secondNode, sortBy);
				isSet = (result != 0);
				
			}else
				isSet=false;
			
			if(!isSet){
				int statusDifference = firstNode.getStatusValue() - secondNode.getStatusValue();
				if (statusDifference != 0) 
					result = (statusDifference < 0 ? -1 : 1);
				else
					result = lastResortCompare(firstNode, secondNode);
			}

			if(result == 0){
				return 0;
			}else if(reversed){
				isReversed = true;
				return result > 0 ? -1 : 1;
			}else{
				isReversed = false;
				return result < 0 ? -1 : 1;
			}
		}

		protected int customCompare(PeerNodeStatus firstNode, PeerNodeStatus secondNode, String sortBy2) {
			if(sortBy.equals("address")){
				return firstNode.getPeerAddress().compareToIgnoreCase(secondNode.getPeerAddress());
			}else if(sortBy.equals("location")){
				return (firstNode.getLocation() - secondNode.getLocation()) < 0 ? -1 : 1; // Shouldn't be equal anyway
			}else if(sortBy.equals("version")){
				return Version.getArbitraryBuildNumber(firstNode.getVersion()) - Version.getArbitraryBuildNumber(secondNode.getVersion());
			}else
				return 0;
		}

		/** Default comparison, after taking into account status */
		protected int lastResortCompare(PeerNodeStatus firstNode, PeerNodeStatus secondNode) {
			return (firstNode.getLocation() - secondNode.getLocation()) < 0 ? -1 : 1;
		}
	}

	protected final Node node;
	protected final NodeClientCore core;
	protected final NodeStats stats;
	protected final PeerManager peers;
	protected boolean isReversed = false;
	protected final DecimalFormat fix1 = new DecimalFormat("##0.0%");
	
	public String supportedMethods() {
		return "GET";
	}

	protected ConnectionsToadlet(Node n, NodeClientCore core, HighLevelSimpleClient client) {
		super(client);
		this.node = n;
		this.core = core;
		this.stats = n.nodeStats;
		this.peers = n.peers;
	}

	public void handleGet(URI uri, final HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		String path = uri.getPath();
		if(path.endsWith("myref.fref")) {
			SimpleFieldSet fs = getNoderef();
			StringWriter sw = new StringWriter();
			fs.writeTo(sw);
			MultiValueTable extraHeaders = new MultiValueTable();
			// Force download to disk
			extraHeaders.put("Content-Disposition", "attachment; filename=myref.fref");
			this.writeReply(ctx, 200, "application/x-freenet-reference", "OK", extraHeaders, sw.toString());
			return;
		}

		if(path.endsWith("myref.txt")) {
			SimpleFieldSet fs = getNoderef();
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
		PeerNodeStatus[] peerNodeStatuses = getPeerNodeStatuses();
		Arrays.sort(peerNodeStatuses, comparator(request.getParam("sortBy", null), request.isParameterSet("reversed")));
		
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
		int numberOfClockProblem = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_CLOCK_PROBLEM);
		
		int numberOfSimpleConnected = numberOfConnected + numberOfRoutingBackedOff;
		int numberOfNotConnected = numberOfTooNew + numberOfTooOld + numberOfDisconnected + numberOfNeverConnected + numberOfDisabled + numberOfBursting + numberOfListening + numberOfListenOnly;
		String titleCountString = null;
		if(advancedModeEnabled) {
			titleCountString = "(" + numberOfConnected + '/' + numberOfRoutingBackedOff + '/' + numberOfTooNew + '/' + numberOfTooOld + '/' + numberOfNotConnected + ')';
		} else {
			titleCountString = (numberOfNotConnected + numberOfSimpleConnected)>0 ? String.valueOf(numberOfSimpleConnected) : "";
		}
		
		HTMLNode pageNode = ctx.getPageMaker().getPageNode(getPageTitle(titleCountString, node.getMyName()), ctx);
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
			if (numberOfClockProblem > 0) {
				HTMLNode peerStatsListenOnlyListItem = peerStatsList.addChild("li").addChild("span");
				peerStatsListenOnlyListItem.addChild("span", new String[] { "class", "title", "style" }, new String[] { "peer_clock_problem", l10n("clockProblem"), "border-bottom: 1px dotted; cursor: help;" }, l10n("clockProblemShort"));
				peerStatsListenOnlyListItem.addChild("span", ":\u00a0" + numberOfClockProblem);
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
				if(hasNameColumn())
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
				if(hasPrivateNoteColumn())
					peerTableHeaderRow.addChild("th").addChild("a", "href", sortString(isReversed, "privnote")).addChild("span", new String[] { "title", "style" }, new String[] { l10n("privateNote"), "border-bottom: 1px dotted; cursor: help;" }, l10n("privateNoteTitle"));

				if(advancedModeEnabled) {
					peerTableHeaderRow.addChild("th", "%\u00a0Time Routable");
					peerTableHeaderRow.addChild("th", "Total\u00a0Traffic\u00a0(in/out)");
					peerTableHeaderRow.addChild("th", "Congestion\u00a0Control");
					peerTableHeaderRow.addChild("th", "Time\u00a0Delta");
				}
				
				for (int peerIndex = 0, peerCount = peerNodeStatuses.length; peerIndex < peerCount; peerIndex++) {
					
					PeerNodeStatus peerNodeStatus = peerNodeStatuses[peerIndex];
					drawRow(peerTable, peerNodeStatus, advancedModeEnabled, fProxyJavascriptEnabled, now, path);
					
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
		referenceInfobox.addChild("div", "class", "infobox-content").addChild("pre", "id", "reference", getNoderef().toString() + '\n');
		
		// our ports
		HTMLNode portInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		portInfobox.addChild("div", "class", "infobox-header", l10n("nodePortsTitle"));
		HTMLNode portInfoboxContent = portInfobox.addChild("div", "class", "infobox-content");
		HTMLNode portInfoList = portInfoboxContent.addChild("ul");
		SimpleFieldSet fproxyConfig = node.config.get("fproxy").exportFieldSet(true);
		SimpleFieldSet fcpConfig = node.config.get("fcp").exportFieldSet(true);
		SimpleFieldSet tmciConfig = node.config.get("console").exportFieldSet(true);
		portInfoList.addChild("li", L10n.getString("DarknetConnectionsToadlet.darknetFnpPort", new String[] { "port" }, new String[] { Integer.toString(node.getFNPPort()) }));
		int opennetPort = node.getOpennetFNPPort();
		if(opennetPort > 0)
			portInfoList.addChild("li", L10n.getString("DarknetConnectionsToadlet.opennetFnpPort", new String[] { "port" }, new String[] { Integer.toString(opennetPort) }));
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
	
	
	protected abstract String getPageTitle(String titleCountString, String myName);

	protected Comparator comparator(String sortBy, boolean reversed) {
		return new ComparatorByStatus(sortBy, reversed);
	}

	abstract protected PeerNodeStatus[] getPeerNodeStatuses();

	abstract protected SimpleFieldSet getNoderef();

	private void drawRow(HTMLNode peerTable, PeerNodeStatus peerNodeStatus, boolean advancedModeEnabled, boolean fProxyJavascriptEnabled, long now, String path) {
		HTMLNode peerRow = peerTable.addChild("tr");

		// check box column
		peerRow.addChild("td", "class", "peer-marker").addChild("input", new String[] { "type", "name" }, new String[] { "checkbox", "node_" + peerNodeStatus.hashCode() });

		// status column
		String statusString = peerNodeStatus.getStatusName();
		if (!advancedModeEnabled && (peerNodeStatus.getStatusValue() == PeerManager.PEER_NODE_STATUS_ROUTING_BACKED_OFF)) {
			statusString = "BUSY";
		}
		peerRow.addChild("td", "class", "peer-status").addChild("span", "class", peerNodeStatus.getStatusCSSName(), statusString + (peerNodeStatus.isFetchingARK() ? "*" : ""));

		drawNameColumn(peerRow, peerNodeStatus);
		
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

		if(hasPrivateNoteColumn())
			drawPrivateNoteColumn(peerRow, peerNodeStatus, fProxyJavascriptEnabled);

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
			// time delta
			peerRow.addChild("td", "class", "peer-idle" /* FIXME */).addChild("#", TimeUtil.formatTime(peerNodeStatus.getClockDelta()));
		}
		
		if (path.endsWith("displaymessagetypes.html")) {
			drawMessageTypes(peerTable, peerNodeStatus);
		}
	}

	/** Is there a name column? */
	abstract protected boolean hasNameColumn();
	
	/**
	 * Draw the name column, if there is one. This will be directly after the status column.
	 */
	abstract protected void drawNameColumn(HTMLNode peerRow, PeerNodeStatus peerNodeStatus);

	/**
	 * Is there a private note column?
	 */
	abstract protected boolean hasPrivateNoteColumn();

	/**
	 * Draw the private note column.
	 */
	abstract protected void drawPrivateNoteColumn(HTMLNode peerRow, PeerNodeStatus peerNodeStatus, boolean fProxyJavascriptEnabled);
	
	private void drawMessageTypes(HTMLNode peerTable, PeerNodeStatus peerNodeStatus) {
		HTMLNode messageCountRow = peerTable.addChild("tr", "class", "message-status");
		messageCountRow.addChild("td", "colspan", "2");
		HTMLNode messageCountCell = messageCountRow.addChild("td", "colspan", "9");  // = total table row width - 2 from above colspan
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

	private String idleToString(long now, long idle) {
		if (idle <= 0) {
			return " ";
		}
		long idleMilliseconds = now - idle;
		return TimeUtil.formatTime(idleMilliseconds);
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


}
