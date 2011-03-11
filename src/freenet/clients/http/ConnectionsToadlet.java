package freenet.clients.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
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
import freenet.l10n.NodeL10n;
import freenet.node.DarknetPeerNode;
import freenet.node.FSParseException;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.NodeStats;
import freenet.node.PeerManager;
import freenet.node.PeerNode;
import freenet.node.DarknetPeerNode.FRIEND_TRUST;
import freenet.node.PeerNode.IncomingLoadSummaryStats;
import freenet.node.PeerNodeStatus;
import freenet.node.Version;
import freenet.support.Fields;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.SimpleFieldSet;
import freenet.support.SizeUtil;
import freenet.support.TimeUtil;
import freenet.support.Logger.LogLevel;
import freenet.support.api.HTTPRequest;

/** Base class for DarknetConnectionsToadlet and OpennetConnectionsToadlet */
public abstract class ConnectionsToadlet extends Toadlet {
	protected class ComparatorByStatus implements Comparator<PeerNodeStatus> {		
		protected final String sortBy;
		protected final boolean reversed;
		
		ComparatorByStatus(String sortBy, boolean reversed) {
			this.sortBy = sortBy;
			this.reversed = reversed;
		}
		
		public int compare(PeerNodeStatus firstNode, PeerNodeStatus secondNode) {
			int result = 0;
			boolean isSet = true;
			
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
		
		// xor: check why we do not just return the result of (long1-long2)
		// j16sdiz: (Long.MAX_VALUE - (-1) ) would overflow and become negative
		private int compareLongs(long long1, long long2) {
			int diff = Long.valueOf(long1).compareTo(long2);
			if(diff == 0)
				return 0;
			else
				return (diff > 0 ? 1 : -1);
		}
		
		private int compareInts(int int1, int int2) {
			int diff = Integer.valueOf(int1).compareTo(int2);
			if(diff == 0)
				return 0;
			else
				return (diff > 0 ? 1 : -1);
		}

		protected int customCompare(PeerNodeStatus firstNode, PeerNodeStatus secondNode, String sortBy2) {
			if(sortBy.equals("address")){
				return firstNode.getPeerAddress().compareToIgnoreCase(secondNode.getPeerAddress());
			}else if(sortBy.equals("location")){
				return compareLocations(firstNode, secondNode);
			}else if(sortBy.equals("version")){
				return Version.getArbitraryBuildNumber(firstNode.getVersion(), -1) - Version.getArbitraryBuildNumber(secondNode.getVersion(), -1);
			}else if(sortBy.equals("backoffRT")){
				return Double.compare(firstNode.getBackedOffPercent(true), secondNode.getBackedOffPercent(true));
			}else if(sortBy.equals("backoffBulk")){
				return Double.compare(firstNode.getBackedOffPercent(false), secondNode.getBackedOffPercent(false));
			}else if(sortBy.equals(("overload_p"))){
				return Double.compare(firstNode.getPReject(), secondNode.getPReject());
			}else if(sortBy.equals(("idle"))){
				return compareLongs(firstNode.getTimeLastConnectionCompleted(), secondNode.getTimeLastConnectionCompleted());
			}else if(sortBy.equals("time_routable")){
				return Double.compare(firstNode.getPercentTimeRoutableConnection(), secondNode.getPercentTimeRoutableConnection());
			}else if(sortBy.equals("total_traffic")){
				long total1 = firstNode.getTotalInputBytes()+firstNode.getTotalOutputBytes();
				long total2 = secondNode.getTotalInputBytes()+secondNode.getTotalOutputBytes();
				return compareLongs(total1, total2);
				}else if(sortBy.equals("total_traffic_since_startup")){
					long total1 = firstNode.getTotalInputSinceStartup()+firstNode.getTotalOutputSinceStartup();
					long total2 = secondNode.getTotalInputSinceStartup()+secondNode.getTotalOutputSinceStartup();
					return compareLongs(total1, total2);
			}else if(sortBy.equals("selection_percentage")){
				return Double.compare(firstNode.getSelectionRate(), secondNode.getSelectionRate());
			}else if(sortBy.equals("time_delta")){
				return compareLongs(firstNode.getClockDelta(), secondNode.getClockDelta());
			}else if(sortBy.equals(("uptime"))){
				return compareInts(firstNode.getReportedUptimePercentage(), secondNode.getReportedUptimePercentage());
			}else
				return 0;
		}

		private int compareLocations(PeerNodeStatus firstNode, PeerNodeStatus secondNode) {
			double diff = firstNode.getLocation() - secondNode.getLocation(); // Can occasionally be the same, and we must have a consistent sort order
			if(Double.MIN_VALUE*2 > Math.abs(diff)) return 0;
			return diff > 0 ? 1 : -1;
		}

		/** Default comparison, after taking into account status */
		protected int lastResortCompare(PeerNodeStatus firstNode, PeerNodeStatus secondNode) {
			return compareLocations(firstNode, secondNode);
		}
	}

	protected final Node node;
	protected final NodeClientCore core;
	protected final NodeStats stats;
	protected final PeerManager peers;
	protected boolean isReversed = false;
	public enum PeerAdditionReturnCodes{ OK, WRONG_ENCODING, CANT_PARSE, INTERNAL_ERROR, INVALID_SIGNATURE, TRY_TO_ADD_SELF, ALREADY_IN_REFERENCE}

	protected ConnectionsToadlet(Node n, NodeClientCore core, HighLevelSimpleClient client) {
		super(client);
		this.node = n;
		this.core = core;
		this.stats = n.nodeStats;
		this.peers = n.peers;
	}

	abstract SimpleColumn[] endColumnHeaders(boolean advancedModeEnabled);
	
	abstract class SimpleColumn {
		abstract protected void drawColumn(HTMLNode peerRow, PeerNodeStatus peerNodeStatus);
		abstract public String getSortString();
		abstract public String getTitleKey();
		abstract public String getExplanationKey();
	}

	public void handleMethodGET(URI uri, final HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
                if (!ctx.isAllowedFullAccess()) {
                        super.sendErrorPage(ctx, 403, NodeL10n.getBase().getString("Toadlet.unauthorizedTitle"), NodeL10n.getBase().getString("Toadlet.unauthorized"));
                        return;
                }

                String path = uri.getPath();
		if(path.endsWith("myref.fref")) {
			SimpleFieldSet fs = getNoderef();
			StringWriter sw = new StringWriter();
			fs.writeTo(sw);
			MultiValueTable<String, String> extraHeaders = new MultiValueTable<String, String>();
			// Force download to disk
			extraHeaders.put("Content-Disposition", "attachment; filename=myref.fref");
			this.writeReply(ctx, 200, "application/x-freenet-reference", "OK", extraHeaders, sw.toString());
			return;
		}

		if(path.endsWith("myref.txt")) {
			SimpleFieldSet fs = getNoderef();
			StringWriter sw = new StringWriter();
			fs.writeTo(sw);
			this.writeTextReply(ctx, 200, "OK", sw.toString());
			return;
		}
		
		final DecimalFormat fix1 = new DecimalFormat("##0.0%");
				
		final boolean fProxyJavascriptEnabled = node.isFProxyJavascriptEnabled();
		boolean drawMessageTypes = path.endsWith("displaymessagetypes.html");
		
		/* gather connection statistics */
		PeerNodeStatus[] peerNodeStatuses = getPeerNodeStatuses(!drawMessageTypes);
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
		int numberOfConnError = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_CONN_ERROR);
		int numberOfDisconnecting = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_DISCONNECTING);
		int numberOfRoutingDisabled = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_ROUTING_DISABLED);
		
		int numberOfSimpleConnected = numberOfConnected + numberOfRoutingBackedOff;
		int numberOfNotConnected = numberOfTooNew + numberOfTooOld + numberOfDisconnected + numberOfNeverConnected + numberOfDisabled + numberOfBursting + numberOfListening + numberOfListenOnly + numberOfClockProblem + numberOfConnError;
		String titleCountString = null;
		if(node.isAdvancedModeEnabled()) {
			titleCountString = "(" + numberOfConnected + '/' + numberOfRoutingBackedOff + '/' + numberOfTooNew + '/' + numberOfTooOld + '/' + numberOfRoutingDisabled + '/' + numberOfNotConnected + ')';
		} else {
			titleCountString = (numberOfNotConnected + numberOfSimpleConnected)>0 ? String.valueOf(numberOfSimpleConnected) : "";
		}
		
		final int mode = ctx.getPageMaker().parseMode(request, container);
		
		PageNode page = ctx.getPageMaker().getPageNode(getPageTitle(titleCountString, node.getMyName()), ctx);
		HTMLNode pageNode = page.outer;
		HTMLNode contentNode = page.content;
		
		// FIXME! We need some nice images
		long now = System.currentTimeMillis();
	
		if(ctx.isAllowedFullAccess())
			contentNode.addChild(core.alerts.createSummary());
		
		if(peerNodeStatuses.length>0){
			
			if(mode >= PageMaker.MODE_ADVANCED) {

				/* node status values */
				long nodeUptimeSeconds = (now - node.startupTime) / 1000;
				int bwlimitDelayTime = (int) stats.getBwlimitDelayTime();
				int nodeAveragePingTime = (int) stats.getNodeAveragePingTime();
				int networkSizeEstimateSession = stats.getDarknetSizeEstimate(-1);
				int networkSizeEstimateRecent = 0;
				if(nodeUptimeSeconds > (48*60*60)) {  // 48 hours
					networkSizeEstimateRecent = stats.getDarknetSizeEstimate(now - (48*60*60*1000));  // 48 hours
				}
				DecimalFormat fix4 = new DecimalFormat("0.0000");
				double routingMissDistanceLocal =  stats.routingMissDistanceLocal.currentValue();
				double routingMissDistanceRemote =  stats.routingMissDistanceRemote.currentValue();
				double routingMissDistanceOverall =  stats.routingMissDistanceOverall.currentValue();
				double backedOffPercent =  stats.backedOffPercent.currentValue();
				String nodeUptimeString = TimeUtil.formatTime(nodeUptimeSeconds * 1000);  // *1000 to convert to milliseconds
				
				// BEGIN OVERVIEW TABLE
				HTMLNode overviewTable = contentNode.addChild("table", "class", "column");
				HTMLNode overviewTableRow = overviewTable.addChild("tr");
				HTMLNode nextTableCell = overviewTableRow.addChild("td", "class", "first");
				
				HTMLNode overviewInfobox = nextTableCell.addChild("div", "class", "infobox");
				overviewInfobox.addChild("div", "class", "infobox-header", "Node status overview");
				HTMLNode overviewInfoboxContent = overviewInfobox.addChild("div", "class", "infobox-content");
				HTMLNode overviewList = overviewInfoboxContent.addChild("ul");
				overviewList.addChild("li", "bwlimitDelayTime:\u00a0" + bwlimitDelayTime + "ms");
				overviewList.addChild("li", "nodeAveragePingTime:\u00a0" + nodeAveragePingTime + "ms");
				overviewList.addChild("li", "darknetSizeEstimateSession:\u00a0" + networkSizeEstimateSession + "\u00a0nodes");
				if(nodeUptimeSeconds > (48*60*60)) {  // 48 hours
					overviewList.addChild("li", "darknetSizeEstimateRecent:\u00a0" + networkSizeEstimateRecent + "\u00a0nodes");
				}
				overviewList.addChild("li", "nodeUptime:\u00a0" + nodeUptimeString);
				overviewList.addChild("li", "routingMissDistanceLocal:\u00a0" + fix4.format(routingMissDistanceLocal));
				overviewList.addChild("li", "routingMissDistanceRemote:\u00a0" + fix4.format(routingMissDistanceRemote));
				overviewList.addChild("li", "routingMissDistanceOverall:\u00a0" + fix4.format(routingMissDistanceOverall));
				overviewList.addChild("li", "backedOffPercent:\u00a0" + fix1.format(backedOffPercent));
				overviewList.addChild("li", "pInstantReject:\u00a0" + fix1.format(stats.pRejectIncomingInstantly()));
				nextTableCell = overviewTableRow.addChild("td");
				
				// Activity box
				int numARKFetchers = node.getNumARKFetchers();
				
				HTMLNode activityInfobox = nextTableCell.addChild("div", "class", "infobox");
				activityInfobox.addChild("div", "class", "infobox-header", l10n("activityTitle"));
				HTMLNode activityInfoboxContent = activityInfobox.addChild("div", "class", "infobox-content");
				HTMLNode activityList = StatisticsToadlet.drawActivity(activityInfoboxContent, node);
				if ((mode >= PageMaker.MODE_ADVANCED) && (activityList != null)) {
					if (numARKFetchers > 0) {
						activityList.addChild("li", "ARK\u00a0Fetch\u00a0Requests:\u00a0" + numARKFetchers);
					}
					StatisticsToadlet.drawBandwidth(activityList, node, nodeUptimeSeconds, mode >= PageMaker.MODE_ADVANCED);
				}
				
				nextTableCell = overviewTableRow.addChild("td", "class", "last");
				
				// Peer statistics box
				HTMLNode peerStatsInfobox = nextTableCell.addChild("div", "class", "infobox");
				StatisticsToadlet.drawPeerStatsBox(peerStatsInfobox, mode >= PageMaker.MODE_ADVANCED, numberOfConnected, numberOfRoutingBackedOff, numberOfTooNew, numberOfTooOld, numberOfDisconnected, numberOfNeverConnected, numberOfDisabled, numberOfBursting, numberOfListening, numberOfListenOnly, 0, 0, numberOfRoutingDisabled, numberOfClockProblem, numberOfConnError, numberOfDisconnecting, node);
				
				// Peer routing backoff reason box
				if(mode >= PageMaker.MODE_ADVANCED) {
					HTMLNode backoffReasonInfobox = nextTableCell.addChild("div", "class", "infobox");
					HTMLNode title = backoffReasonInfobox.addChild("div", "class", "infobox-header", "Peer backoff reasons (realtime)");
					HTMLNode backoffReasonContent = backoffReasonInfobox.addChild("div", "class", "infobox-content");
					String [] routingBackoffReasons = peers.getPeerNodeRoutingBackoffReasons(true);
					int total = 0;
					if(routingBackoffReasons.length == 0) {
						backoffReasonContent.addChild("#", "Good, your node is not backed off from any peers!");
					} else {
						HTMLNode reasonList = backoffReasonContent.addChild("ul");
						for(int i=0;i<routingBackoffReasons.length;i++) {
							int reasonCount = peers.getPeerNodeRoutingBackoffReasonSize(routingBackoffReasons[i], true);
							if(reasonCount > 0) {
								total += reasonCount;
								reasonList.addChild("li", routingBackoffReasons[i] + '\u00a0' + reasonCount);
							}
						}
					}
					if(total > 0)
						title.addChild("#", ": "+total);
					backoffReasonInfobox = nextTableCell.addChild("div", "class", "infobox");
					title = backoffReasonInfobox.addChild("div", "class", "infobox-header", "Peer backoff reasons (bulk)");
					backoffReasonContent = backoffReasonInfobox.addChild("div", "class", "infobox-content");
					routingBackoffReasons = peers.getPeerNodeRoutingBackoffReasons(false);
					total = 0;
					if(routingBackoffReasons.length == 0) {
						backoffReasonContent.addChild("#", "Good, your node is not backed off from any peers!");
					} else {
						HTMLNode reasonList = backoffReasonContent.addChild("ul");
						for(int i=0;i<routingBackoffReasons.length;i++) {
							int reasonCount = peers.getPeerNodeRoutingBackoffReasonSize(routingBackoffReasons[i], false);
							if(reasonCount > 0) {
								total += reasonCount;
								reasonList.addChild("li", routingBackoffReasons[i] + '\u00a0' + reasonCount);
							}
						}
					}
					if(total > 0)
						title.addChild("#", ": "+total);
				}
				// END OVERVIEW TABLE
			}
			
			boolean enablePeerActions = showPeerActionsBox();
			
			// BEGIN PEER TABLE
			if(fProxyJavascriptEnabled) {
				StringBuilder jsBuf = new StringBuilder();
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
			peerTableInfoboxHeader.addChild("#", getPeerListTitle());
			if (mode >= PageMaker.MODE_ADVANCED) {
				if (!path.endsWith("displaymessagetypes.html")) {
					peerTableInfoboxHeader.addChild("#", " ");
					peerTableInfoboxHeader.addChild("a", "href", "displaymessagetypes.html", "(more detailed)");
				}
			}
			HTMLNode peerTableInfoboxContent = peerTableInfobox.addChild("div", "class", "infobox-content");

			if (peerNodeStatuses.length == 0) {
				NodeL10n.getBase().addL10nSubstitution(peerTableInfoboxContent, "DarknetConnectionsToadlet.noPeersWithHomepageLink", 
						new String[] { "link" }, new HTMLNode[] { HTMLNode.link("/") });
			} else {
				HTMLNode peerForm = null;
				HTMLNode peerTable;
				if(enablePeerActions) {
					peerForm = ctx.addFormChild(peerTableInfoboxContent, ".", "peersForm");
					peerTable = peerForm.addChild("table", "class", "darknet_connections");
				} else {
					peerTable = peerTableInfoboxContent.addChild("table", "class", "darknet_connections");
				}
				HTMLNode peerTableHeaderRow = peerTable.addChild("tr");
				if(enablePeerActions)
					peerTableHeaderRow.addChild("th");
				peerTableHeaderRow.addChild("th").addChild("a", "href", sortString(isReversed, "status")).addChild("#", l10n("statusTitle"));
				if(hasNameColumn())
					peerTableHeaderRow.addChild("th").addChild("a", "href", sortString(isReversed, "name")).addChild("span", new String[] { "title", "style" }, new String[] { l10n("nameClickToMessage"), "border-bottom: 1px dotted; cursor: help;" }, l10n("nameTitle"));
				if(hasTrustColumn())
					peerTableHeaderRow.addChild("th").addChild("a", "href", sortString(isReversed, "trust")).addChild("span", new String[] { "title", "style" }, new String[] { l10n("trustMessage"), "border-bottom: 1px dotted; cursor: help;" }, l10n("trustTitle"));
				if (mode >= PageMaker.MODE_ADVANCED) {
					peerTableHeaderRow.addChild("th").addChild("a", "href", sortString(isReversed, "address")).addChild("span", new String[] { "title", "style" }, new String[] { l10n("ipAddress"), "border-bottom: 1px dotted; cursor: help;" }, l10n("ipAddressTitle"));
				}
				peerTableHeaderRow.addChild("th").addChild("a", "href", sortString(isReversed, "version")).addChild("#", l10n("versionTitle"));
				if (mode >= PageMaker.MODE_ADVANCED) {
					peerTableHeaderRow.addChild("th").addChild("a", "href", sortString(isReversed, "location")).addChild("#", "Location");
					peerTableHeaderRow.addChild("th").addChild("a", "href", sortString(isReversed, "backoffRT")).addChild("span", new String[] { "title", "style" }, new String[] { "Other node busy (realtime)? Display: Percentage of time the node is overloaded, Current wait time remaining (0=not overloaded)/total/last overload reason", "border-bottom: 1px dotted; cursor: help;" }, "Backoff (realtime)");
					peerTableHeaderRow.addChild("th").addChild("a", "href", sortString(isReversed, "backoffBulk")).addChild("span", new String[] { "title", "style" }, new String[] { "Other node busy (bulk)? Display: Percentage of time the node is overloaded, Current wait time remaining (0=not overloaded)/total/last overload reason", "border-bottom: 1px dotted; cursor: help;" }, "Backoff (bulk)");

					peerTableHeaderRow.addChild("th").addChild("a", "href", sortString(isReversed, "overload_p")).addChild("span", new String[] { "title", "style" }, new String[] { "Probability of the node rejecting a request due to overload or causing a timeout.", "border-bottom: 1px dotted; cursor: help;" }, "Overload Probability");
				}
				peerTableHeaderRow.addChild("th").addChild("a", "href", sortString(isReversed, "idle")).addChild("span", new String[] { "title", "style" }, new String[] { l10n("idleTime"), "border-bottom: 1px dotted; cursor: help;" }, l10n("idleTimeTitle"));
				if(hasPrivateNoteColumn())
					peerTableHeaderRow.addChild("th").addChild("a", "href", sortString(isReversed, "privnote")).addChild("span", new String[] { "title", "style" }, new String[] { l10n("privateNote"), "border-bottom: 1px dotted; cursor: help;" }, l10n("privateNoteTitle"));
 
				if(mode >= PageMaker.MODE_ADVANCED) {
					peerTableHeaderRow.addChild("th").addChild("a", "href", sortString(isReversed, "time_routable")).addChild("#", "%\u00a0Time Routable");
					peerTableHeaderRow.addChild("th").addChild("a", "href", sortString(isReversed, "selection_percentage")).addChild("#", "%\u00a0Selection");
					peerTableHeaderRow.addChild("th").addChild("a", "href", sortString(isReversed, "total_traffic")).addChild("#", "Total\u00a0Traffic\u00a0(in/out/resent)");
					peerTableHeaderRow.addChild("th").addChild("a", "href", sortString(isReversed, "total_traffic_since_startup")).addChild("#", "Total\u00a0Traffic\u00a0(in/out) since startup");
					peerTableHeaderRow.addChild("th", "Congestion\u00a0Control");
					peerTableHeaderRow.addChild("th").addChild("a", "href", sortString(isReversed, "time_delta")).addChild("#", "Time\u00a0Delta");
					peerTableHeaderRow.addChild("th").addChild("a", "href", sortString(isReversed, "uptime")).addChild("#", "Reported\u00a0Uptime");
					peerTableHeaderRow.addChild("th", "Transmit\u00a0Queue");
					peerTableHeaderRow.addChild("th", "Peer\u00a0Capacity\u00a0Bulk");
					peerTableHeaderRow.addChild("th", "Peer\u00a0Capacity\u00a0Realtime");
				}
				
				SimpleColumn[] endCols = endColumnHeaders(mode >= PageMaker.MODE_ADVANCED);
				if(endCols != null) {
					for(int i=0;i<endCols.length;i++) {
						SimpleColumn col = endCols[i];
						HTMLNode header = peerTableHeaderRow.addChild("th");
						String sortString = col.getSortString();
						if(sortString != null)
							header = header.addChild("a", "href", sortString(isReversed, sortString));
						header.addChild("span", new String[] { "title", "style" }, new String[] { NodeL10n.getBase().getString(col.getExplanationKey()), "border-bottom: 1px dotted; cursor: help;" }, NodeL10n.getBase().getString(col.getTitleKey()));
					}
				}

				double totalSelectionRate = 0.0;
				for(PeerNodeStatus status : peerNodeStatuses) {
					totalSelectionRate += status.getSelectionRate();
				}
				for (int peerIndex = 0, peerCount = peerNodeStatuses.length; peerIndex < peerCount; peerIndex++) {					
					PeerNodeStatus peerNodeStatus = peerNodeStatuses[peerIndex];
					drawRow(peerTable, peerNodeStatus, mode >= PageMaker.MODE_ADVANCED, fProxyJavascriptEnabled, now, path, enablePeerActions, endCols, drawMessageTypes, totalSelectionRate, fix1);
					
				}

				if(peerForm != null) {
					drawPeerActionSelectBox(peerForm, mode >= PageMaker.MODE_ADVANCED);
				}
			}
			// END PEER TABLE
		} else {
			if(!isOpennet()) {
				try {
					throw new RedirectException("/addfriend/");
				} catch (URISyntaxException e) {
					Logger.error(this, "Impossible: "+e+" for /addfriend/", e);
				}
			}
		}
		
		// our reference
		if(shouldDrawNoderefBox(mode >= PageMaker.MODE_ADVANCED)) {
			drawAddPeerBox(contentNode, ctx);
			drawNoderefBox(contentNode, ctx);
		}
		
		this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
	}

	protected abstract boolean acceptRefPosts();
	
	/** Where to redirect to if there is an error */
	protected abstract String defaultRedirectLocation();

	public void handleMethodPOST(URI uri, final HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		boolean logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
		
		if(!acceptRefPosts()) {
			super.sendErrorPage(ctx, 403, "Unauthorized", NodeL10n.getBase().getString("Toadlet.unauthorized"));
			return;
		}
		
		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, "Unauthorized", NodeL10n.getBase().getString("Toadlet.unauthorized"));
			return;
		}
		
		String pass = request.getPartAsString("formPassword", 32);
		if((pass == null) || !pass.equals(core.formPassword)) {
			MultiValueTable<String, String> headers = new MultiValueTable<String, String>();
			headers.put("Location", defaultRedirectLocation());
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			if(logMINOR) Logger.minor(this, "No password ("+pass+" should be "+core.formPassword+ ')');
			return;
		}
		
		if (request.isPartSet("add")) {
			// add a new node
			String urltext = request.getPartAsString("url", 200);
			urltext = urltext.trim();
			String reftext = request.getPartAsString("ref", Integer.MAX_VALUE);
			reftext = reftext.trim();
			if (reftext.length() < 200) {
				reftext = request.getPartAsString("reffile", Integer.MAX_VALUE);
				reftext = reftext.trim();
			}
			String privateComment = null;
			if(!isOpennet())
				privateComment = request.getPartAsString("peerPrivateNote", 250).trim();
			
			String trustS = request.getPartAsStringFailsafe("trust", 10);
			FRIEND_TRUST trust = null;
			if(trustS != null && !trustS.equals(""))
				trust = FRIEND_TRUST.valueOf(trustS);
			
			if(trust == null && !isOpennet()) {
				// FIXME: Layering violation. Ideally DarknetPeerNode would do this check.
				this.sendErrorPage(ctx, 200, l10n("noTrustLevelAddingFriendTitle"), l10n("noTrustLevelAddingFriend"), !isOpennet());
				return;
			}
			
			StringBuilder ref = new StringBuilder(1024);
			if (urltext.length() > 0) {
				// fetch reference from a URL
				BufferedReader in = null;
				try {
					URL url = new URL(urltext);
					URLConnection uc = url.openConnection();
					// FIXME get charset encoding from uc.getContentType()
					in = new BufferedReader(new InputStreamReader(uc.getInputStream()));
					String line;
					while ((line = in.readLine()) != null) {
						ref.append( line ).append('\n');
					}
				} catch (IOException e) {
					this.sendErrorPage(ctx, 200, l10n("failedToAddNodeTitle"), NodeL10n.getBase().getString("DarknetConnectionsToadlet.cantFetchNoderefURL", new String[] { "url" }, new String[] { urltext }), !isOpennet());
					return;
				} finally {
					if( in != null ){
						in.close();
					}
				}
			} else if (reftext.length() > 0) {
				// read from post data or file upload
				// this slightly scary looking regexp chops any extra characters off the beginning or ends of lines and removes extra line breaks
				ref = new StringBuilder(reftext.replaceAll(".*?((?:[\\w,\\.]+\\=[^\r\n]+?)|(?:End))[ \\t]*(?:\\r?\\n)+", "$1\n"));
			} else {
				this.sendErrorPage(ctx, 200, l10n("failedToAddNodeTitle"), l10n("noRefOrURL"), !isOpennet());
				request.freeParts();
				return;
			}
			ref = new StringBuilder(ref.toString().trim());

			request.freeParts();

			//Split the references string, because the peers are added individually
			// FIXME split by lines at this point rather than in addNewNode would be more efficient
			int idx;
			while((idx = ref.indexOf("\r\n")) > -1) {
				ref.deleteCharAt(idx);
			}
			while((idx = ref.indexOf("\r")) > -1) {
				// Mac's just use \r
				ref.setCharAt(idx, '\n');
			}
			String[] nodesToAdd=ref.toString().split("\nEnd\n");
			//The peer's additions results
			Map<PeerAdditionReturnCodes,Integer> results=new HashMap<PeerAdditionReturnCodes, Integer>();
			for(int i=0;i<nodesToAdd.length;i++){
				//We need to trim then concat 'End' to the node's reference, this way we have a normal reference(the split() removes the 'End'-s!)
				PeerAdditionReturnCodes result=addNewNode(nodesToAdd[i].trim().concat("\nEnd"), privateComment, trust);
				//Store the result
				if(results.containsKey(result)==false){
					results.put(result, Integer.valueOf(0));
				}
				results.put(result, results.get(result)+1);
			}
			
			PageNode page = ctx.getPageMaker().getPageNode(l10n("reportOfNodeAddition"), ctx);
			HTMLNode pageNode = page.outer;
			HTMLNode contentNode = page.content;
			
			//We create a table to show the results
			HTMLNode detailedStatusBox=new HTMLNode("table");
			//Header of the table
			detailedStatusBox.addChild(new HTMLNode("tr")).addChildren(new HTMLNode[]{new HTMLNode("th",l10n("resultName")),new HTMLNode("th",l10n("numOfResults"))});
			HTMLNode statusBoxTable=detailedStatusBox.addChild(new HTMLNode("tbody"));
			//Iterate through the return codes
			for(PeerAdditionReturnCodes returnCode:PeerAdditionReturnCodes.values()){
				if(results.containsKey(returnCode)){
					//Add a <tr> and 2 <td> with the name of the code and the number of occasions it happened. If the code is OK, we use green, red elsewhere.
					statusBoxTable.addChild(new HTMLNode("tr","style","color:"+(returnCode==PeerAdditionReturnCodes.OK?"green":"red"))).addChildren(new HTMLNode[]{new HTMLNode("td",l10n("peerAdditionCode."+returnCode.toString())),new HTMLNode("td",results.get(returnCode).toString())});
				}
			}

			HTMLNode infoboxContent = ctx.getPageMaker().getInfobox("infobox",l10n("reportOfNodeAddition"), contentNode, "node-added", true);
			infoboxContent.addChild(detailedStatusBox);
			if(!isOpennet())
				infoboxContent.addChild("p").addChild("a", "href", "/addfriend/", l10n("addAnotherFriend"));
			infoboxContent.addChild("p").addChild("a", "href", path(), l10n("goFriendConnectionStatus"));
			addHomepageLink(infoboxContent.addChild("p"));
			
			writeHTMLReply(ctx, 500, l10n("reportOfNodeAddition"), pageNode.generate());
		} else handleAltPost(uri, request, ctx, logMINOR);
		
		
	}
	
	/** Adds a new node. If any error arises, it returns the appropriate return code.
	 * @param nodeReference - The reference to the new node
	 * @param privateComment - The private comment when adding a Darknet node
	 * @param trust 
	 * @param request To pull any extra fields from
	 * @return The result of the addition*/
	private PeerAdditionReturnCodes addNewNode(String nodeReference,String privateComment, FRIEND_TRUST trust){
		SimpleFieldSet fs;
		
		try {
			nodeReference = Fields.trimLines(nodeReference);
			fs = new SimpleFieldSet(nodeReference, false, true);
			if(!fs.getEndMarker().endsWith("End")) {
				Logger.error(this, "Trying to add noderef with end marker \""+fs.getEndMarker()+"\"");
				return PeerAdditionReturnCodes.WRONG_ENCODING;
			}
			fs.setEndMarker("End"); // It's always End ; the regex above doesn't always grok this
		} catch (IOException e) {
			return PeerAdditionReturnCodes.CANT_PARSE;
		} catch (Throwable t) {
			return PeerAdditionReturnCodes.INTERNAL_ERROR;
		}
		PeerNode pn;
		try {
			if(isOpennet()) {
				pn = node.createNewOpennetNode(fs);
			} else {
				pn = node.createNewDarknetNode(fs, trust);
				((DarknetPeerNode)pn).setPrivateDarknetCommentNote(privateComment);
			}
		} catch (FSParseException e1) {
			return PeerAdditionReturnCodes.CANT_PARSE;
		} catch (PeerParseException e1) {
			return PeerAdditionReturnCodes.CANT_PARSE;
		} catch (ReferenceSignatureVerificationException e1){
			return PeerAdditionReturnCodes.INVALID_SIGNATURE;
		} catch (Throwable t) {
			return PeerAdditionReturnCodes.INTERNAL_ERROR;
		}
		if(Arrays.equals(pn.getIdentity(), node.getDarknetIdentity())) {
			return PeerAdditionReturnCodes.TRY_TO_ADD_SELF;
		}
		if(!this.node.addPeerConnection(pn)) {
			return PeerAdditionReturnCodes.ALREADY_IN_REFERENCE;
		}
		return PeerAdditionReturnCodes.OK;
	}

	/** Adding a darknet node or an opennet node? */
	protected abstract boolean isOpennet();

	/**
	 * Rest of handlePost() method - supplied by subclass.
	 * @throws IOException 
	 * @throws ToadletContextClosedException 
	 * @throws RedirectException 
	 */
	protected void handleAltPost(URI uri, HTTPRequest request, ToadletContext ctx, boolean logMINOR) throws ToadletContextClosedException, IOException, RedirectException {
		// Do nothing - we only support adding nodes
		handleMethodGET(uri, new HTTPRequestImpl(uri, "GET"), ctx);
	}

	/**
	 * What should the heading (before "(more detailed)") be on the peers table?
	 */
	protected abstract String getPeerListTitle();

	/** Should there be a checkbox for each peer, and drawPeerActionSelectBox() be called directly
	 * after drawing the peers list? */
	protected abstract boolean showPeerActionsBox();

	/** If showPeerActionsBox() is true, this will be called directly after drawing the peers table.
	 * A form has been added, and checkboxes added for each peer. This function should draw the rest
	 * of the form - any additional controls and one or more submit buttons.
	 */
	protected abstract void drawPeerActionSelectBox(HTMLNode peerForm, boolean advancedModeEnabled);
	
	protected abstract boolean shouldDrawNoderefBox(boolean advancedModeEnabled);

	private void drawNoderefBox(HTMLNode contentNode, ToadletContext ctx) {
		drawNoderefBox(contentNode, ctx, getNoderef());
	}
	
	static final HTMLNode REF_LINK = HTMLNode.link("myref.fref").setReadOnly();
	static final HTMLNode REFTEXT_LINK = HTMLNode.link("myref.txt").setReadOnly();
	
	static void drawNoderefBox(HTMLNode contentNode, ToadletContext ctx, SimpleFieldSet fs) {
		HTMLNode referenceInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		HTMLNode headerReferenceInfobox = referenceInfobox.addChild("div", "class", "infobox-header");
		// FIXME better way to deal with this sort of thing???
		NodeL10n.getBase().addL10nSubstitution(headerReferenceInfobox, "DarknetConnectionsToadlet.myReferenceHeader",
				new String[] { "linkref", "linktext" },
				new HTMLNode[] { REF_LINK, REFTEXT_LINK });
		HTMLNode referenceInfoboxContent = referenceInfobox.addChild("div", "class", "infobox-content");
		HTMLNode warningSentence = referenceInfoboxContent.addChild("p");
		NodeL10n.getBase().addL10nSubstitution(warningSentence, "DarknetConnectionsToadlet.referenceCopyWarning",
				new String[] { "bold" },
				new HTMLNode[] { HTMLNode.STRONG });
		referenceInfoboxContent.addChild("pre", "id", "reference", fs.toString() + '\n');
	}

	protected abstract String getPageTitle(String titleCountString, String myName);

	/** Draw the add a peer box. This comes immediately after the main peers table and before the noderef box.
	 * Implementors may skip it by not doing anything in this method. */
	protected void drawAddPeerBox(HTMLNode contentNode, ToadletContext ctx) {
		drawAddPeerBox(contentNode, ctx, isOpennet(), path());
	}
	
	protected static void drawAddPeerBox(HTMLNode contentNode, ToadletContext ctx, boolean isOpennet, String formTarget) {
		// BEGIN PEER ADDITION BOX
		HTMLNode peerAdditionInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		peerAdditionInfobox.addChild("div", "class", "infobox-header", l10n("addPeerTitle"));
		HTMLNode peerAdditionContent = peerAdditionInfobox.addChild("div", "class", "infobox-content");
		HTMLNode peerAdditionForm = ctx.addFormChild(peerAdditionContent, formTarget, "addPeerForm");
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
		if(!isOpennet) {
			peerAdditionForm.addChild("b", l10n("peerTrustTitle"));
			peerAdditionForm.addChild("#", " ");
			peerAdditionForm.addChild("#", l10n("peerTrustIntroduction"));
			for(FRIEND_TRUST trust : FRIEND_TRUST.valuesBackwards()) { // FIXME reverse order
				HTMLNode input = peerAdditionForm.addChild("br").addChild("input", new String[] { "type", "name", "value" }, new String[] { "radio", "trust", trust.name() });
				input.addChild("b", l10n("peerTrust."+trust.name())); // FIXME l10n
				input.addChild("#", ": ");
				input.addChild("#", l10n("peerTrustExplain."+trust.name()));
			}
			peerAdditionForm.addChild("br");
		}
		 
		if(!isOpennet) {
			peerAdditionForm.addChild("#", (l10n("enterDescription") + ' '));
			peerAdditionForm.addChild("input", new String[] { "id", "type", "name", "size", "maxlength", "value" }, new String[] { "peerPrivateNote", "text", "peerPrivateNote", "16", "250", "" });
			peerAdditionForm.addChild("br");
		}
		peerAdditionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "add", l10n("add") });
	}

	protected Comparator<PeerNodeStatus> comparator(String sortBy, boolean reversed) {
		return new ComparatorByStatus(sortBy, reversed);
	}

	abstract protected PeerNodeStatus[] getPeerNodeStatuses(boolean noHeavy);

	abstract protected SimpleFieldSet getNoderef();

	private void drawRow(HTMLNode peerTable, PeerNodeStatus peerNodeStatus, boolean advancedModeEnabled, boolean fProxyJavascriptEnabled, long now, String path, boolean enablePeerActions, SimpleColumn[] endCols, boolean drawMessageTypes, double totalSelectionRate, DecimalFormat fix1) {
		double selectionRate = peerNodeStatus.getSelectionRate();
		int peerSelectionPercentage = 0;
		if(totalSelectionRate > 0)
			peerSelectionPercentage = (int) (selectionRate * 100 / totalSelectionRate);
		HTMLNode peerRow = peerTable.addChild("tr", "class", "darknet_connections_"+(peerSelectionPercentage > PeerNode.SELECTION_PERCENTAGE_WARNING ? "warning" : "normal"));
		
		if(enablePeerActions) {
			// check box column
			peerRow.addChild("td", "class", "peer-marker").addChild("input", new String[] { "type", "name" }, new String[] { "checkbox", "node_" + peerNodeStatus.hashCode() });
		}

		// status column
		String statusString = peerNodeStatus.getStatusName();
		if (!advancedModeEnabled && (peerNodeStatus.getStatusValue() == PeerManager.PEER_NODE_STATUS_ROUTING_BACKED_OFF)) {
			statusString = "BUSY";
		}
		peerRow.addChild("td", "class", "peer-status").addChild("span", "class", peerNodeStatus.getStatusCSSName(), NodeL10n.getBase().getString("ConnectionsToadlet.nodeStatus." + statusString) + (peerNodeStatus.isFetchingARK() ? "*" : ""));

		drawNameColumn(peerRow, peerNodeStatus);
		
		drawTrustColumn(peerRow, peerNodeStatus);
		
		// address column
		if (advancedModeEnabled) {
			String pingTime = "";
			if (peerNodeStatus.isConnected()) {
				pingTime = " (" + (int) peerNodeStatus.getAveragePingTime() + "ms / " +
				(int) peerNodeStatus.getAveragePingTimeCorrected()+"ms)";
			}
			peerRow.addChild("td", "class", "peer-address").addChild("#", ((peerNodeStatus.getPeerAddress() != null) ? (peerNodeStatus.getPeerAddress() + ':' + peerNodeStatus.getPeerPort()) : (l10n("unknownAddress"))) + pingTime);
		}

		// version column
		if (peerNodeStatus.getStatusValue() != PeerManager.PEER_NODE_STATUS_NEVER_CONNECTED && (peerNodeStatus.isPublicInvalidVersion() || peerNodeStatus.isPublicReverseInvalidVersion())) {  // Don't draw attention to a version problem if NEVER CONNECTED
			peerRow.addChild("td", "class", "peer-version").addChild("span", "class", "peer_version_problem", Integer.toString(peerNodeStatus.getSimpleVersion()));
		} else {
			peerRow.addChild("td", "class", "peer-version").addChild("#", Integer.toString(peerNodeStatus.getSimpleVersion()));
		}

		// location column
		if (advancedModeEnabled) {
			HTMLNode locationNode = peerRow.addChild("td", "class", "peer-location");
			locationNode.addChild("b", String.valueOf(peerNodeStatus.getLocation()));
			locationNode.addChild("br");
			double[] peersLoc = peerNodeStatus.getPeersLocation();
			if(peersLoc != null) {
				for(double loc : peersLoc)
					locationNode.addChild("i", String.valueOf(loc)).addChild("br");
			}
		}

		if (advancedModeEnabled) {
			// backoff column
			HTMLNode backoffCell = peerRow.addChild("td", "class", "peer-backoff");
			backoffCell.addChild("#", fix1.format(peerNodeStatus.getBackedOffPercent(true)));
			int backoff = (int) (Math.max(peerNodeStatus.getRoutingBackedOffUntil(true) - now, 0));
			// Don't list the backoff as zero before it's actually zero
			if ((backoff > 0) && (backoff < 1000)) {
				backoff = 1000;
			}
			backoffCell.addChild("#", ' ' + String.valueOf(backoff / 1000) + '/' + String.valueOf(peerNodeStatus.getRoutingBackoffLength(true) / 1000));
			backoffCell.addChild("#", (peerNodeStatus.getLastBackoffReason(true) == null) ? "" : ('/' + (peerNodeStatus.getLastBackoffReason(true))));

			// backoff column
			backoffCell = peerRow.addChild("td", "class", "peer-backoff");
			backoffCell.addChild("#", fix1.format(peerNodeStatus.getBackedOffPercent(false)));
			backoff = (int) (Math.max(peerNodeStatus.getRoutingBackedOffUntil(false) - now, 0));
			// Don't list the backoff as zero before it's actually zero
			if ((backoff > 0) && (backoff < 1000)) {
				backoff = 1000;
			}
			backoffCell.addChild("#", ' ' + String.valueOf(backoff / 1000) + '/' + String.valueOf(peerNodeStatus.getRoutingBackoffLength(false) / 1000));
			backoffCell.addChild("#", (peerNodeStatus.getLastBackoffReason(false) == null) ? "" : ('/' + (peerNodeStatus.getLastBackoffReason(false))));

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
			// selection stats
			peerRow.addChild("td", "class", "peer-idle" /* FIXME */).addChild("#", (totalSelectionRate > 0 ? (peerSelectionPercentage+"%") : "N/A"));
			// total traffic column
			long sent = peerNodeStatus.getTotalOutputBytes();
			long resent = peerNodeStatus.getResendBytesSent();
			long received = peerNodeStatus.getTotalInputBytes();
			peerRow.addChild("td", "class", "peer-idle" /* FIXME */).addChild("#", SizeUtil.formatSize(received)+" / "+SizeUtil.formatSize(sent)+"/"+SizeUtil.formatSize(resent)+" ("+fix1.format(((double)resent) / ((double)sent))+")");
			// total traffic column startup
			peerRow.addChild("td", "class", "peer-idle" /* FIXME */).addChild("#", SizeUtil.formatSize(peerNodeStatus.getTotalInputSinceStartup())+" / "+SizeUtil.formatSize(peerNodeStatus.getTotalOutputSinceStartup()));
			// congestion control
			PacketThrottle t = peerNodeStatus.getThrottle();
			String val;
			if(t == null)
				val = "none";
			else
				val = (int)t.getBandwidth()+"B/sec delay "+
					t.getDelay()+"ms (RTT "+t.getRoundTripTime()+"ms window "+t.getWindowSize()+')';
			peerRow.addChild("td", "class", "peer-idle" /* FIXME */).addChild("#", val);
			// time delta
			peerRow.addChild("td", "class", "peer-idle" /* FIXME */).addChild("#", TimeUtil.formatTime(peerNodeStatus.getClockDelta()));
			peerRow.addChild("td", "class", "peer-idle" /* FIXME */).addChild("#", peerNodeStatus.getReportedUptimePercentage()+"%");
			peerRow.addChild("td", "class", "peer-idle" /* FIXME */).addChild("#", SizeUtil.formatSize(peerNodeStatus.getMessageQueueLengthBytes())+":"+TimeUtil.formatTime(peerNodeStatus.getMessageQueueLengthTime()));
			IncomingLoadSummaryStats loadStatsBulk = peerNodeStatus.incomingLoadStatsBulk;
			if(loadStatsBulk == null)
				peerRow.addChild("td", "class", "peer-idle" /* FIXME */);
			else
				peerRow.addChild("td", "class", "peer-idle" /* FIXME */).addChild("#", loadStatsBulk.runningRequestsTotal+"reqs:out:"+SizeUtil.formatSize(loadStatsBulk.usedCapacityOutputBytes)+"/"+SizeUtil.formatSize(loadStatsBulk.othersUsedCapacityOutputBytes)+"/"+SizeUtil.formatSize(loadStatsBulk.peerCapacityOutputBytes)+"/"+SizeUtil.formatSize(loadStatsBulk.totalCapacityOutputBytes)+":in:"+SizeUtil.formatSize(loadStatsBulk.usedCapacityInputBytes)+"/"+SizeUtil.formatSize(loadStatsBulk.othersUsedCapacityInputBytes)+"/"+SizeUtil.formatSize(loadStatsBulk.peerCapacityInputBytes)+"/"+SizeUtil.formatSize(loadStatsBulk.totalCapacityInputBytes));
			IncomingLoadSummaryStats loadStatsRT = peerNodeStatus.incomingLoadStatsRealTime;
			if(loadStatsRT == null)
				peerRow.addChild("td", "class", "peer-idle" /* FIXME */);
			else
				peerRow.addChild("td", "class", "peer-idle" /* FIXME */).addChild("#", loadStatsRT.runningRequestsTotal+"reqs:out:"+SizeUtil.formatSize(loadStatsRT.usedCapacityOutputBytes)+"/"+SizeUtil.formatSize(loadStatsRT.othersUsedCapacityOutputBytes)+"/"+SizeUtil.formatSize(loadStatsRT.peerCapacityOutputBytes)+"/"+SizeUtil.formatSize(loadStatsRT.totalCapacityOutputBytes)+":in:"+SizeUtil.formatSize(loadStatsRT.usedCapacityInputBytes)+"/"+SizeUtil.formatSize(loadStatsRT.othersUsedCapacityInputBytes)+"/"+SizeUtil.formatSize(loadStatsRT.peerCapacityInputBytes)+"/"+SizeUtil.formatSize(loadStatsRT.totalCapacityInputBytes));
		}
		
		if(endCols != null) {
			for(int i=0;i<endCols.length;i++) {
				endCols[i].drawColumn(peerRow, peerNodeStatus);
			}
		}
		
		if (drawMessageTypes) {
			drawMessageTypes(peerTable, peerNodeStatus);
		}
	}

	protected boolean hasTrustColumn() {
		return false;
	}

	protected void drawTrustColumn(HTMLNode peerRow, PeerNodeStatus peerNodeStatus) {
		// Do nothing
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
		List<String> messageNames = new ArrayList<String>();
		Map<String, Long[]> messageCounts = new HashMap<String, Long[]>();
		for (Map.Entry<String,Long> entry : peerNodeStatus.getLocalMessagesReceived().entrySet() ) {
			String messageName = entry.getKey();
			Long messageCount = entry.getValue();
			messageNames.add(messageName);
			messageCounts.put(messageName, new Long[] { messageCount, Long.valueOf(0) });
		}
		for (Map.Entry<String,Long> entry : peerNodeStatus.getLocalMessagesSent().entrySet() ) {
			String messageName =  entry.getKey();
			Long messageCount = entry.getValue();
			if (!messageNames.contains(messageName)) {
				messageNames.add(messageName);
			}
			Long[] existingCounts = messageCounts.get(messageName);
			if (existingCounts == null) {
				messageCounts.put(messageName, new Long[] { Long.valueOf(0), messageCount });
			} else {
				existingCounts[1] = messageCount;
			}
		}
		Collections.sort(messageNames, new Comparator<String>() {
			public int compare(String first, String second) {
				return first.compareToIgnoreCase(second);
			}
		});
		for (Iterator<String> messageNamesIterator = messageNames.iterator(); messageNamesIterator.hasNext(); ) {
			String messageName = messageNamesIterator.next();
			Long[] messageCount = messageCounts.get(messageName);
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
		return NodeL10n.getBase().getString("DarknetConnectionsToadlet."+string);
	}
	
	private String sortString(boolean isReversed, String type) {
		return (isReversed ? ("?sortBy="+type) : ("?sortBy="+type+"&reversed"));
	}
	
	/**
	 * Send a simple error page.
	 */
	protected void sendErrorPage(ToadletContext ctx, int code, String desc, String message, boolean returnToAddFriends) throws ToadletContextClosedException, IOException {
		PageNode page = ctx.getPageMaker().getPageNode(desc, ctx);
		HTMLNode pageNode = page.outer;
		HTMLNode contentNode = page.content;
		
		HTMLNode infoboxContent = ctx.getPageMaker().getInfobox("infobox-error", desc, contentNode, null, true);
		infoboxContent.addChild("#", message);
		if(returnToAddFriends) {
			infoboxContent.addChild("br");
			infoboxContent.addChild("a", "href", DarknetAddRefToadlet.PATH, l10n("returnToAddAFriendPage"));
			infoboxContent.addChild("br");
		} else {
			infoboxContent.addChild("br");
			infoboxContent.addChild("a", "href", ".", l10n("returnToPrevPage"));
			infoboxContent.addChild("br");
		}
		addHomepageLink(infoboxContent);
		
		writeHTMLReply(ctx, code, desc, pageNode.generate());
	}

}
