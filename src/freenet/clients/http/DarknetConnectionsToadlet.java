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
import freenet.config.SubConfig;
import freenet.io.comm.IOStatisticCollector;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.io.xfer.PacketThrottle;
import freenet.node.FSParseException;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.PeerNode;
import freenet.node.PeerNodeStatus;
import freenet.node.Version;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.SimpleFieldSet;
import freenet.support.SizeUtil;
import freenet.support.TimeUtil;
import freenet.support.api.Bucket;

public class DarknetConnectionsToadlet extends Toadlet {
	
	private final Node node;
	private final NodeClientCore core;
	private boolean isReversed = false;
	
	protected DarknetConnectionsToadlet(Node n, NodeClientCore core, HighLevelSimpleClient client) {
		super(client);
		this.node = n;
		this.core = core;
	}

	public String supportedMethods() {
		return "GET, POST";
	}

	public void handleGet(URI uri, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		final HTTPRequest request = new HTTPRequest(uri);
		
		String path = uri.getPath();
		if(path.endsWith("myref.fref")) {
			SimpleFieldSet fs = node.exportPublicFieldSet();
			StringWriter sw = new StringWriter();
			fs.writeTo(sw);
			this.writeReply(ctx, 200, "text/plain", "OK", sw.toString());
			return;
		}
		
		final boolean advancedEnabled = node.isAdvancedDarknetEnabled();
		final boolean fProxyJavascriptEnabled = node.isFProxyJavascriptEnabled();
		
		/* gather connection statistics */
		PeerNodeStatus[] peerNodeStatuses = node.getPeerNodeStatuses();
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
		
		int numberOfConnected = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, Node.PEER_NODE_STATUS_CONNECTED);
		int numberOfRoutingBackedOff = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, Node.PEER_NODE_STATUS_ROUTING_BACKED_OFF);
		int numberOfTooNew = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, Node.PEER_NODE_STATUS_TOO_NEW);
		int numberOfTooOld = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, Node.PEER_NODE_STATUS_TOO_OLD);
		int numberOfDisconnected = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, Node.PEER_NODE_STATUS_DISCONNECTED);
		int numberOfNeverConnected = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, Node.PEER_NODE_STATUS_NEVER_CONNECTED);
		int numberOfDisabled = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, Node.PEER_NODE_STATUS_DISABLED);
		int numberOfBursting = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, Node.PEER_NODE_STATUS_BURSTING);
		int numberOfListening = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, Node.PEER_NODE_STATUS_LISTENING);
		int numberOfListenOnly = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, Node.PEER_NODE_STATUS_LISTEN_ONLY);
		
		int numberOfSimpleConnected = numberOfConnected + numberOfRoutingBackedOff;
		int numberOfNotConnected = numberOfTooNew + numberOfTooOld + numberOfDisconnected + numberOfNeverConnected + numberOfDisabled + numberOfBursting + numberOfListening + numberOfListenOnly;
		String titleCountString = null;
		if(advancedEnabled) {
			titleCountString = "(" + numberOfConnected + '/' + numberOfRoutingBackedOff + '/' + numberOfTooNew + '/' + numberOfTooOld + '/' + numberOfNotConnected + ')';
		} else {
			titleCountString = (numberOfNotConnected + numberOfSimpleConnected)>0 ? String.valueOf(numberOfSimpleConnected) : "";
		}
		
		HTMLNode pageNode = ctx.getPageMaker().getPageNode(titleCountString + " Darknet Peers of " + node.getMyName());
		HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
		
		// FIXME! We need some nice images
		long now = System.currentTimeMillis();
	
		contentNode.addChild(core.alerts.createSummary());
		
		if(peerNodeStatuses.length>0){

			/* node status values */
			long nodeUptimeSeconds = (now - node.startupTime) / 1000;
			int bwlimitDelayTime = (int) node.getBwlimitDelayTime();
			int nodeAveragePingTime = (int) node.getNodeAveragePingTime();
			int networkSizeEstimateSession = node.getNetworkSizeEstimate(-1);
			int networkSizeEstimateRecent = 0;
			if(nodeUptimeSeconds > (48*60*60)) {  // 48 hours
				networkSizeEstimateRecent = node.getNetworkSizeEstimate(now - (48*60*60*1000));  // 48 hours
			}
			DecimalFormat fix4 = new DecimalFormat("0.0000");
			double routingMissDistance =  node.routingMissDistance.currentValue();
			DecimalFormat fix1 = new DecimalFormat("##0.0%");
			double backedOffPercent =  node.backedOffPercent.currentValue();
			String nodeUptimeString = TimeUtil.formatTime(nodeUptimeSeconds * 1000);  // *1000 to convert to milliseconds

			// BEGIN OVERVIEW TABLE
			HTMLNode overviewTable = contentNode.addChild("table", "class", "column");
			HTMLNode overviewTableRow = overviewTable.addChild("tr");
			HTMLNode nextTableCell = overviewTableRow.addChild("td", "class", "first");

			/* node status overview box */
			if(advancedEnabled) {
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
				overviewList.addChild("li", "pInstantReject:\u00a0" + fix1.format(node.pRejectIncomingInstantly()));
				nextTableCell = overviewTableRow.addChild("td");
			}

			// Activity box
			int numInserts = node.getNumInserts();
			int numRequests = node.getNumRequests();
			int numTransferringRequests = node.getNumTransferringRequests();
			int numARKFetchers = node.getNumARKFetchers();

			HTMLNode activityInfobox = nextTableCell.addChild("div", "class", "infobox");
			activityInfobox.addChild("div", "class", "infobox-header", "Current activity");
			HTMLNode activityInfoboxContent = activityInfobox.addChild("div", "class", "infobox-content");
			if ((numInserts == 0) && (numRequests == 0) && (numTransferringRequests == 0) && (numARKFetchers == 0)) {
				activityInfoboxContent.addChild("#", "Your node is not processing any requests right now.");
			} else {
				HTMLNode activityList = activityInfoboxContent.addChild("ul");
				if (numInserts > 0) {
					activityList.addChild("li", "Inserts:\u00a0" + numInserts);
				}
				if (numRequests > 0) {
					activityList.addChild("li", "Requests:\u00a0" + numRequests);
				}
				if (numTransferringRequests > 0) {
					activityList.addChild("li", "Transferring\u00a0Requests:\u00a0" + numTransferringRequests);
				}
				if (advancedEnabled) {
					if (numARKFetchers > 0) {
						activityList.addChild("li", "ARK\u00a0Fetch\u00a0Requests:\u00a0" + numARKFetchers);
					}
					long[] total = IOStatisticCollector.getTotalIO();
					long total_output_rate = (total[0]) / nodeUptimeSeconds;
					long total_input_rate = (total[1]) / nodeUptimeSeconds;
					long totalPayload = node.getTotalPayloadSent();
					long total_payload_rate = totalPayload / nodeUptimeSeconds;
					int percent = (int) (100 * totalPayload / total[0]);
					activityList.addChild("li", "Total Output:\u00a0" + SizeUtil.formatSize(total[0], true) + "\u00a0(" + SizeUtil.formatSize(total_output_rate, true) + "ps)");
					activityList.addChild("li", "Payload Output:\u00a0" + SizeUtil.formatSize(totalPayload, true) + "\u00a0(" + SizeUtil.formatSize(total_payload_rate, true) + "ps) ("+percent+"%)");
					activityList.addChild("li", "Total Input:\u00a0" + SizeUtil.formatSize(total[1], true) + "\u00a0(" + SizeUtil.formatSize(total_input_rate, true) + "ps)");
					long[] rate = node.getNodeIOStats();
					long delta = (rate[5] - rate[2]) / 1000;
					long output_rate = (rate[3] - rate[0]) / delta;
					long input_rate = (rate[4] - rate[1]) / delta;
					SubConfig nodeConfig = node.config.get("node");
					int outputBandwidthLimit = nodeConfig.getInt("outputBandwidthLimit");
					int inputBandwidthLimit = nodeConfig.getInt("inputBandwidthLimit");
					if(inputBandwidthLimit == -1) {
						inputBandwidthLimit = outputBandwidthLimit * 4;
					}
					activityList.addChild("li", "Output Rate:\u00a0" + SizeUtil.formatSize(output_rate, true) + "ps (of\u00a0"+SizeUtil.formatSize(outputBandwidthLimit, true)+"ps)");
					activityList.addChild("li", "Input Rate:\u00a0" + SizeUtil.formatSize(input_rate, true) + "ps (of\u00a0"+SizeUtil.formatSize(inputBandwidthLimit, true)+"ps)");
				}
			}

			nextTableCell = advancedEnabled ? overviewTableRow.addChild("td") : overviewTableRow.addChild("td", "class", "last");

			// Peer statistics box
			HTMLNode peerStatsInfobox = nextTableCell.addChild("div", "class", "infobox");
			peerStatsInfobox.addChild("div", "class", "infobox-header", "Peer statistics");
			HTMLNode peerStatsContent = peerStatsInfobox.addChild("div", "class", "infobox-content");
			HTMLNode peerStatsList = peerStatsContent.addChild("ul");
			if (numberOfConnected > 0) {
				HTMLNode peerStatsConnectedListItem = peerStatsList.addChild("li").addChild("span");
				peerStatsConnectedListItem.addChild("span", new String[] { "class", "title", "style" }, new String[] { "peer_connected", "Connected: We're successfully connected to these nodes", "border-bottom: 1px dotted; cursor: help;" }, "Connected");
				peerStatsConnectedListItem.addChild("span", ":\u00a0" + numberOfConnected);
			}
			if (numberOfRoutingBackedOff > 0) {
				HTMLNode peerStatsRoutingBackedOffListItem = peerStatsList.addChild("li").addChild("span");
				peerStatsRoutingBackedOffListItem.addChild("span", new String[] { "class", "title", "style" }, new String[] { "peer_backed_off", (advancedEnabled ? "Connected but backed off: These peers are connected but we're backed off of them" : "Busy: These peers are connected but they're busy") + ", so the node is not routing requests to them", "border-bottom: 1px dotted; cursor: help;" }, advancedEnabled ? "Backed off" : "Busy");
				peerStatsRoutingBackedOffListItem.addChild("span", ":\u00a0" + numberOfRoutingBackedOff);
			}
			if (numberOfTooNew > 0) {
				HTMLNode peerStatsTooNewListItem = peerStatsList.addChild("li").addChild("span");
				peerStatsTooNewListItem.addChild("span", new String[] { "class", "title", "style" }, new String[] { "peer_too_new", "Connected but too new: These peers' minimum mandatory build is higher than this node's build. This node is not routing requests to them", "border-bottom: 1px dotted; cursor: help;" }, "Too New");
				peerStatsTooNewListItem.addChild("span", ":\u00a0" + numberOfTooNew);
			}
			if (numberOfTooOld > 0) {
				HTMLNode peerStatsTooOldListItem = peerStatsList.addChild("li").addChild("span");
				peerStatsTooOldListItem.addChild("span", new String[] { "class", "title", "style" }, new String[] { "peer_too_old", "Connected but too old: This node's minimum mandatory build is higher than these peers' build. This node is not routing requests to them", "border-bottom: 1px dotted; cursor: help;" }, "Too Old");
				peerStatsTooOldListItem.addChild("span", ":\u00a0" + numberOfTooOld);
			}
			if (numberOfDisconnected > 0) {
				HTMLNode peerStatsDisconnectedListItem = peerStatsList.addChild("li").addChild("span");
				peerStatsDisconnectedListItem.addChild("span", new String[] { "class", "title", "style" }, new String[] { "peer_disconnected", "Not connected: No connection so far but this node is continuously trying to connect", "border-bottom: 1px dotted; cursor: help;" }, "Disconnected");
				peerStatsDisconnectedListItem.addChild("span", ":\u00a0" + numberOfDisconnected);
			}
			if (numberOfNeverConnected > 0) {
				HTMLNode peerStatsNeverConnectedListItem = peerStatsList.addChild("li").addChild("span");
				peerStatsNeverConnectedListItem.addChild("span", new String[] { "class", "title", "style" }, new String[] { "peer_never_connected", "Never Connected: The node has never connected with these peers", "border-bottom: 1px dotted; cursor: help;" }, "Never Connected");
				peerStatsNeverConnectedListItem.addChild("span", ":\u00a0" + numberOfNeverConnected);
			}
			if (numberOfDisabled > 0) {
				HTMLNode peerStatsDisabledListItem = peerStatsList.addChild("li").addChild("span");
				peerStatsDisabledListItem.addChild("span", new String[] { "class", "title", "style" }, new String[] { "peer_disabled", "Not connected and disabled: because the user has instructed to not connect to peers ", "border-bottom: 1px dotted; cursor: help;" }, "Disabled");
				peerStatsDisabledListItem.addChild("span", ":\u00a0" + numberOfDisabled);
			}
			if (numberOfBursting > 0) {
				HTMLNode peerStatsBurstingListItem = peerStatsList.addChild("li").addChild("span");
				peerStatsBurstingListItem.addChild("span", new String[] { "class", "title", "style" }, new String[] { "peer_bursting", "Not connected and bursting: this node is, for a short period, trying to connect to these peers because the user has set BurstOnly on them", "border-bottom: 1px dotted; cursor: help;" }, "Bursting");
				peerStatsBurstingListItem.addChild("span", ":\u00a0" + numberOfBursting);
			}
			if (numberOfListening > 0) {
				HTMLNode peerStatsListeningListItem = peerStatsList.addChild("li").addChild("span");
				peerStatsListeningListItem.addChild("span", new String[] { "class", "title", "style" }, new String[] { "peer_listening", "Not connected but listening: this node won't try to connect to these peers very often because the user has set BurstOnly on them", "border-bottom: 1px dotted; cursor: help;" }, "Listening");
				peerStatsListeningListItem.addChild("span", ":\u00a0" + numberOfListening);
			}
			if (numberOfListenOnly > 0) {
				HTMLNode peerStatsListenOnlyListItem = peerStatsList.addChild("li").addChild("span");
				peerStatsListenOnlyListItem.addChild("span", new String[] { "class", "title", "style" }, new String[] { "peer_listen_only", "Not connected and listen only: this node won't try to connect to these peers at all because the user has set ListenOnly on them", "border-bottom: 1px dotted; cursor: help;" }, "Listen Only");
				peerStatsListenOnlyListItem.addChild("span", ":\u00a0" + numberOfListenOnly);
			}

			// Peer routing backoff reason box
			if(advancedEnabled) {
				nextTableCell = overviewTableRow.addChild("td", "class", "last");
				HTMLNode backoffReasonInfobox = nextTableCell.addChild("div", "class", "infobox");
				backoffReasonInfobox.addChild("div", "class", "infobox-header", "Peer backoff reasons");
				HTMLNode backoffReasonContent = backoffReasonInfobox.addChild("div", "class", "infobox-content");
				String [] routingBackoffReasons = node.getPeerNodeRoutingBackoffReasons();
				if(routingBackoffReasons.length == 0) {
					backoffReasonContent.addChild("#", "Good, your node is not backed off from any peers!");
				} else {
					HTMLNode reasonList = backoffReasonContent.addChild("ul");
					for(int i=0;i<routingBackoffReasons.length;i++) {
						int reasonCount = node.getPeerNodeRoutingBackoffReasonSize(routingBackoffReasons[i]);
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
			peerTableInfoboxHeader.addChild("#", "My peers");
			if (advancedEnabled) {
				if (!path.endsWith("displaymessagetypes.html")) {
					peerTableInfoboxHeader.addChild("#", " ");
					peerTableInfoboxHeader.addChild("a", "href", "displaymessagetypes.html", "(more detailed)");
				}
			}
			HTMLNode peerTableInfoboxContent = peerTableInfobox.addChild("div", "class", "infobox-content");

			if (peerNodeStatuses.length == 0) {
				peerTableInfoboxContent.addChild("#", "Freenet can not work as you have not added any peers so far. Please go to the ");
				peerTableInfoboxContent.addChild("a", "href", "/", "node homepage");
				peerTableInfoboxContent.addChild("#", " and read the top infobox to see how it is done.");
			} else {
				HTMLNode peerForm = ctx.addFormChild(peerTableInfoboxContent, ".", "peersForm");
				HTMLNode peerTable = peerForm.addChild("table", "class", "darknet_connections");
				HTMLNode peerTableHeaderRow = peerTable.addChild("tr");
				peerTableHeaderRow.addChild("th");
				peerTableHeaderRow.addChild("th").addChild("a", "href", sortString(isReversed, "status")).addChild("#", "Status");
				peerTableHeaderRow.addChild("th").addChild("a", "href", sortString(isReversed, "name")).addChild("span", new String[] { "title", "style" }, new String[] { "The node's name. Click on the name link to send the node a N2NTM (Node To Node Text Message)", "border-bottom: 1px dotted; cursor: help;" }, "Name");
				if (advancedEnabled) {
					peerTableHeaderRow.addChild("th").addChild("a", "href", sortString(isReversed, "address")).addChild("span", new String[] { "title", "style" }, new String[] { "The node's network address as IP:Port", "border-bottom: 1px dotted; cursor: help;" }, "Address");
				}
				peerTableHeaderRow.addChild("th").addChild("a", "href", sortString(isReversed, "version")).addChild("#", "Version");
				if (advancedEnabled) {
					peerTableHeaderRow.addChild("th").addChild("a", "href", sortString(isReversed, "location")).addChild("#", "Location");
					peerTableHeaderRow.addChild("th").addChild("span", new String[] { "title", "style" }, new String[] { "Other node busy? Display: Percentage of time the node is overloaded, Current wait time remaining (0=not overloaded)/total/last overload reason", "border-bottom: 1px dotted; cursor: help;" }, "Backoff");

					peerTableHeaderRow.addChild("th").addChild("span", new String[] { "title", "style" }, new String[] { "Probability of the node rejecting a request due to overload or causing a timeout.", "border-bottom: 1px dotted; cursor: help;" }, "Overload Probability");
				}
				peerTableHeaderRow.addChild("th").addChild("span", new String[] { "title", "style" }, new String[] { "How long since the node was connected or last seen", "border-bottom: 1px dotted; cursor: help;" }, "Connected\u00a0/\u00a0Idle");
				peerTableHeaderRow.addChild("th").addChild("a", "href", sortString(isReversed, "privnote")).addChild("span", new String[] { "title", "style" }, new String[] { "A private note concerning this peer", "border-bottom: 1px dotted; cursor: help;" }, "Private Note");

				if(advancedEnabled) {
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
					if (!advancedEnabled && (peerNodeStatus.getStatusValue() == Node.PEER_NODE_STATUS_ROUTING_BACKED_OFF)) {
						statusString = "BUSY";
					}
					peerRow.addChild("td", "class", "peer-status").addChild("span", "class", peerNodeStatus.getStatusCSSName(), statusString + (peerNodeStatus.isFetchingARK() ? "*" : ""));

					// name column
					peerRow.addChild("td", "class", "peer-name").addChild("a", "href", "/send_n2ntm/?peernode_hashcode=" + peerNodeStatus.hashCode(), peerNodeStatus.getName());

					// address column
					if (advancedEnabled) {
						String pingTime = "";
						if (peerNodeStatus.isConnected()) {
							pingTime = " (" + (int) peerNodeStatus.getAveragePingTime() + "ms)";
						}
						peerRow.addChild("td", "class", "peer-address").addChild("#", ((peerNodeStatus.getPeerAddress() != null) ? (peerNodeStatus.getPeerAddress() + ':' + peerNodeStatus.getPeerPort()) : ("(unknown address)")) + pingTime);
					}

					// version column
					if (peerNodeStatus.getStatusValue() != Node.PEER_NODE_STATUS_NEVER_CONNECTED && (peerNodeStatus.isPublicInvalidVersion() || peerNodeStatus.isPublicReverseInvalidVersion())) {  // Don't draw attention to a version problem if NEVER CONNECTED
						peerRow.addChild("td", "class", "peer-version").addChild("span", "class", "peer_version_problem", peerNodeStatus.getSimpleVersion());
					} else {
						peerRow.addChild("td", "class", "peer-version").addChild("#", peerNodeStatus.getSimpleVersion());
					}

					// location column
					if (advancedEnabled) {
						peerRow.addChild("td", "class", "peer-location", String.valueOf(peerNodeStatus.getLocation()));
					}

					if (advancedEnabled) {
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
					} else if (peerNodeStatus.getStatusValue() == Node.PEER_NODE_STATUS_NEVER_CONNECTED) {
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

					if(advancedEnabled) {
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
						HTMLNode messageCountCell = messageCountRow.addChild("td", "colspan", String.valueOf(advancedEnabled ? 9 : 5));  // = total table row width - 2 from above colspan
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
				actionSelect.addChild("option", "value", "", "-- Select action --");
				actionSelect.addChild("option", "value", "send_n2ntm", "Send N2NTM to selected peers");
				actionSelect.addChild("option", "value", "update_notes", "Update changed private notes");
				if(advancedEnabled) {
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
				actionSelect.addChild("option", "value", "", "-- -- --");
				actionSelect.addChild("option", "value", "remove", "Remove selected peers");
				peerForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "doAction", "Go" });
			}
			// END PEER TABLE

		}
		
		// BEGIN PEER ADDITION BOX
		HTMLNode peerAdditionInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		peerAdditionInfobox.addChild("div", "class", "infobox-header", "Add another peer");
		HTMLNode peerAdditionContent = peerAdditionInfobox.addChild("div", "class", "infobox-content");
		HTMLNode peerAdditionForm = ctx.addFormChild(peerAdditionContent, ".", "addPeerForm");
		peerAdditionForm.addChild("#", "Paste the reference here:");
		peerAdditionForm.addChild("br");
		peerAdditionForm.addChild("textarea", new String[] { "id", "name", "rows", "cols" }, new String[] { "reftext", "ref", "8", "74" });
		peerAdditionForm.addChild("br");
		peerAdditionForm.addChild("#", "Enter the URL of the reference here: ");
		peerAdditionForm.addChild("input", new String[] { "id", "type", "name" }, new String[] { "refurl", "text", "url" });
		peerAdditionForm.addChild("br");
		peerAdditionForm.addChild("#", "Choose the file containing the reference here: ");
		peerAdditionForm.addChild("input", new String[] { "id", "type", "name" }, new String[] { "reffile", "file", "reffile" });
		peerAdditionForm.addChild("br");
		peerAdditionForm.addChild("#", "Enter a node description: ");
		peerAdditionForm.addChild("input", new String[] { "id", "type", "name", "size", "maxlength", "value" }, new String[] { "peerPrivateNote", "text", "peerPrivateNote", "16", "250", "" });
		peerAdditionForm.addChild("br");
		peerAdditionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "add", "Add" });
		
		// our reference
		HTMLNode referenceInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		referenceInfobox.addChild("div", "class", "infobox-header").addChild("a", "href", "myref.fref", "My reference");
		referenceInfobox.addChild("div", "class", "infobox-content").addChild("pre", "id", "reference", node.exportPublicFieldSet().toString());
		
		// our ports
		HTMLNode portInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		portInfobox.addChild("div", "class", "infobox-header", "Node's Ports");
		HTMLNode portInfoboxContent = portInfobox.addChild("div", "class", "infobox-content");
		HTMLNode portInfoList = portInfoboxContent.addChild("ul");
		SimpleFieldSet fproxyConfig = node.config.get("fproxy").exportFieldSet(true);
		SimpleFieldSet fcpConfig = node.config.get("fcp").exportFieldSet(true);
		SimpleFieldSet tmciConfig = node.config.get("console").exportFieldSet(true);
		portInfoList.addChild("li", "FNP:\u00a0" + node.getFNPPort() + "/udp\u00a0\u00a0\u00a0(between nodes; this is usually the only port that you might want to port forward)");
		try {
			if(fproxyConfig.getBoolean("enabled", false)) {
				portInfoList.addChild("li", "FProxy:\u00a0" + fproxyConfig.getInt("port") + "/tcp\u00a0\u00a0\u00a0(this web interface)");
			} else {
				portInfoList.addChild("li", "FProxy:\u00a0disabled/tcp\u00a0\u00a0\u00a0(this web interface)");
			}
			if(fcpConfig.getBoolean("enabled", false)) {
				portInfoList.addChild("li", "FCP:\u00a0" + fcpConfig.getInt("port") + "/tcp\u00a0\u00a0\u00a0(for Freenet clients such as Frost and Thaw)");
			} else {
				portInfoList.addChild("li", "FCP:\u00a0disabled/tcp\u00a0\u00a0\u00a0(for Freenet clients such as Frost and Thaw)");
			}
			if(tmciConfig.getBoolean("enabled", false)) {
				portInfoList.addChild("li", "TMCI:\u00a0" + tmciConfig.getInt("port") + "/tcp\u00a0\u00a0\u00a0(simple telnet-based command-line interface)");
			} else {
				portInfoList.addChild("li", "TMCI:\u00a0disabled/tcp\u00a0\u00a0\u00a0(simple telnet-based command-line interface)");
			}
		} catch (FSParseException e) {
			// ignore
		}
		
		StringBuffer pageBuffer = new StringBuffer();
		pageNode.generate(pageBuffer);
		this.writeReply(ctx, 200, "text/html", "OK", pageBuffer.toString());
	}
	
	private String sortString(boolean isReversed, String type) {
		return (isReversed ? ("?sortBy="+type) : ("?sortBy="+type+"&reversed"));
	}

	public void handlePost(URI uri, Bucket data, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		if(data.size() > 1024*1024) {
			this.writeReply(ctx, 400, "text/plain", "Too big", "Too much data, darknet toadlet limited to 1MB");
			return;
		}
		
		HTTPRequest request = new HTTPRequest(uri, data, ctx);
		
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		
		String pass = request.getPartAsString("formPassword", 32);
		if((pass == null) || !pass.equals(core.formPassword)) {
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/darknet/");
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
					this.sendErrorPage(ctx, 200, "Failed To Add Node", "Unable to retrieve node reference from " + urltext + ". Please try again.");
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
				this.sendErrorPage(ctx, 200, "Failed To Add Node", "Could not detect either a node reference or a URL. Please try again.");
				request.freeParts();
				return;
			}
			ref = new StringBuffer(ref.toString().trim());

			request.freeParts();
			// we have a node reference in ref
			SimpleFieldSet fs;
			
			try {
				fs = new SimpleFieldSet(ref.toString());
				fs.setEndMarker("End"); // It's always End ; the regex above doesn't always grok this
			} catch (IOException e) {
				this.sendErrorPage(ctx, 200, "Failed To Add Node", "Unable to parse the given text as a node reference ("+e+"). Please try again.");
				return;
			}
			PeerNode pn;
			try {
				pn = new PeerNode(fs, this.node, false);
				pn.setPrivateDarknetCommentNote(privateComment);
			} catch (FSParseException e1) {
				this.sendErrorPage(ctx, 200, "Failed To Add Node", "Unable to parse the given text as a node reference ("+e1+"). Please try again.");
				return;
			} catch (PeerParseException e1) {
				this.sendErrorPage(ctx, 200, "Failed To Add Node", "Unable to parse the given text as a node reference ("+e1+"). Please try again.");
				return;
			} catch (ReferenceSignatureVerificationException e1){
				HTMLNode node = new HTMLNode("div");
				node.addChild("#", "Unable to verify the signature of the given reference ("+e1+").");
				node.addChild("br");
				this.sendErrorPage(ctx, 200, "Failed To Add Node", node);
				return;
			}
			if(pn.getIdentityHash()==node.getIdentityHash()) {
				this.sendErrorPage(ctx, 200, "Failed To Add Node", "You can\u2019t add your own node to the list of remote peers.");
				return;
			}
			if(!this.node.addDarknetConnection(pn)) {
				this.sendErrorPage(ctx, 200, "Failed To Add Node", "We already have the given reference.");
				return;
			}
			
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/darknet/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		} else if (request.isPartSet("doAction") && request.getPartAsString("action",25).equals("send_n2ntm")) {
			HTMLNode pageNode = ctx.getPageMaker().getPageNode("Send Node to Node Text Message");
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
			String resultString = N2NTMToadlet.createN2NTMSendForm( pageNode, contentNode, ctx, peers);
			if(resultString != null) {  // was there an error in createN2NTMSendForm()?
				this.writeReply(ctx, 200, "text/html", "OK", resultString);
				return;
			}
			StringBuffer pageBuffer = new StringBuffer();
			pageNode.generate(pageBuffer);
			this.writeReply(ctx, 200, "text/html", "OK", pageBuffer.toString());
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
			headers.put("Location", "/darknet/");
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
			headers.put("Location", "/darknet/");
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
			headers.put("Location", "/darknet/");
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
			headers.put("Location", "/darknet/");
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
			headers.put("Location", "/darknet/");
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
			headers.put("Location", "/darknet/");
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
			headers.put("Location", "/darknet/");
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
			headers.put("Location", "/darknet/");
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
			headers.put("Location", "/darknet/");
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
			headers.put("Location", "/darknet/");
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
			headers.put("Location", "/darknet/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		} else if (request.isPartSet("remove") || (request.isPartSet("doAction") && request.getPartAsString("action",25).equals("remove"))) {			
			if(logMINOR) Logger.minor(this, "Remove node");
			
			PeerNode[] peerNodes = node.getDarknetConnections();
			for(int i = 0; i < peerNodes.length; i++) {
				if (request.isPartSet("node_"+peerNodes[i].hashCode())) {	
					if((peerNodes[i].timeLastConnectionCompleted() < (System.currentTimeMillis() - 1000*60*60*24*7) /* one week */) ||  (peerNodes[i].peerNodeStatus == Node.PEER_NODE_STATUS_NEVER_CONNECTED) || request.isPartSet("forceit")){
						this.node.removeDarknetConnection(peerNodes[i]);
						if(logMINOR) Logger.minor(this, "Removed node: node_"+peerNodes[i].hashCode());
					}else{
						if(logMINOR) Logger.minor(this, "Refusing to remove : node_"+peerNodes[i].hashCode()+" (trying to prevent network churn) : let's display the warning message.");
						HTMLNode pageNode = ctx.getPageMaker().getPageNode("Please confirm");
						HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
						HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-warning", "Node removal"));
						HTMLNode content = ctx.getPageMaker().getContentNode(infobox);
						content.addChild("p").addChild("#", "Are you sure you wish to remove "+peerNodes[i].getName()+" ? Before it has at least one week downtime, it's not recommended to do so, as it may be down only temporarily, and many users cannot run their nodes 24x7.");
						HTMLNode removeForm = ctx.addFormChild(content, "/darknet/", "removeConfirmForm");
						removeForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "node_"+peerNodes[i].hashCode(), "remove" });
						removeForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", "Cancel" });
						removeForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "remove", "Remove it!" });
						removeForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "forceit", "Force" });

						writeReply(ctx, 200, "text/html", "OK", pageNode.generate());
						return; // FIXME: maybe it breaks multi-node removing
					}				
				} else {
					if(logMINOR) Logger.minor(this, "Part not set: node_"+peerNodes[i].hashCode());
				}
			}
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/darknet/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		} else {
			this.handleGet(uri, ctx);
		}
	}
	
	private String idleToString(long now, long idle) {
		if (idle == -1) {
			return " ";
		}
		long idleMilliseconds = now - idle;
		return TimeUtil.formatTime(idleMilliseconds);
	}
}
