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
import freenet.node.FSParseException;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.PeerNode;
import freenet.node.PeerNodeStatus;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.SimpleFieldSet;
import freenet.support.io.Bucket;

public class DarknetConnectionsToadlet extends Toadlet {

	public class MyComparator implements Comparator {

		public int compare(Object arg0, Object arg1) {
			Object[] row0 = (Object[])arg0;
			Object[] row1 = (Object[])arg1;
			Integer stat0 = (Integer) row0[2];  // 2 = status
			Integer stat1 = (Integer) row1[2];
			int x = stat0.compareTo(stat1);
			if(x != 0) return x;
			String name0 = (String) row0[9];  // 9 = node name
			String name1 = (String) row1[9];
			return name0.toLowerCase().compareTo(name1.toLowerCase());
		}

	}

	private final Node node;
	private final NodeClientCore core;
	
	protected DarknetConnectionsToadlet(Node n, NodeClientCore core, HighLevelSimpleClient client) {
		super(client);
		this.node = n;
		this.core = core;
	}

	public String supportedMethods() {
		return "GET, POST";
	}

	/**
	 * Counts the peers in <code>peerNodes</code> that have the specified
	 * status.
	 * @param peerNodeStatuses The peer nodes' statuses
	 * @param status The status to count
	 * @return The number of peers that have the specified status.
	 */
	private int getPeerStatusCount(PeerNodeStatus[] peerNodeStatuses, int status) {
		int count = 0;
		for (int peerIndex = 0, peerCount = peerNodeStatuses.length; peerIndex < peerCount; peerIndex++) {
			if (peerNodeStatuses[peerIndex].getStatusValue() == status) {
				count++;
			}
		}
		return count;
	}

	public void handleGet(URI uri, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		
		String path = uri.getPath();
		if(path.endsWith("myref.txt")) {
			SimpleFieldSet fs = node.exportPublicFieldSet();
			StringWriter sw = new StringWriter();
			fs.writeTo(sw);
			this.writeReply(ctx, 200, "text/plain", "OK", sw.toString());
			return;
		}
		
		final boolean advancedEnabled = node.isAdvancedDarknetEnabled();
		
		/* gather connection statistics */
		PeerNodeStatus[] peerNodeStatuses = node.getPeerNodeStatuses();

		Arrays.sort(peerNodeStatuses, new Comparator() {
			public int compare(Object first, Object second) {
				PeerNodeStatus firstNode = (PeerNodeStatus) first;
				PeerNodeStatus secondNode = (PeerNodeStatus) second;
				int statusDifference = firstNode.getStatusValue() - secondNode.getStatusValue();
				if (statusDifference != 0) {
					return statusDifference;
				}
				return firstNode.getName().compareTo(secondNode.getName());
			}
		});
		
		int numberOfConnected = getPeerStatusCount(peerNodeStatuses, Node.PEER_NODE_STATUS_CONNECTED);
		int numberOfRoutingBackedOff = getPeerStatusCount(peerNodeStatuses, Node.PEER_NODE_STATUS_ROUTING_BACKED_OFF);
		int numberOfTooNew = getPeerStatusCount(peerNodeStatuses, Node.PEER_NODE_STATUS_TOO_NEW);
		int numberOfTooOld = getPeerStatusCount(peerNodeStatuses, Node.PEER_NODE_STATUS_TOO_OLD);
		int numberOfDisconnected = getPeerStatusCount(peerNodeStatuses, Node.PEER_NODE_STATUS_DISCONNECTED);
		int numberOfNeverConnected = getPeerStatusCount(peerNodeStatuses, Node.PEER_NODE_STATUS_NEVER_CONNECTED);
		int numberOfDisabled = getPeerStatusCount(peerNodeStatuses, Node.PEER_NODE_STATUS_DISABLED);
		int numberOfBursting = getPeerStatusCount(peerNodeStatuses, Node.PEER_NODE_STATUS_BURSTING);
		int numberOfListening = getPeerStatusCount(peerNodeStatuses, Node.PEER_NODE_STATUS_LISTENING);
		int numberOfListenOnly = getPeerStatusCount(peerNodeStatuses, Node.PEER_NODE_STATUS_LISTEN_ONLY);
		
		int numberOfSimpleConnected = numberOfConnected + numberOfRoutingBackedOff;
		int numberOfNotConnected = numberOfTooNew + numberOfTooOld + numberOfDisconnected + numberOfNeverConnected + numberOfDisabled + numberOfBursting + numberOfListening + numberOfListenOnly;
		String titleCountString = null;
		if(advancedEnabled) {
			titleCountString = "(" + numberOfConnected + "/" + numberOfRoutingBackedOff + "/" + numberOfTooNew + "/" + numberOfTooOld + "/" + numberOfNotConnected + ")";
		} else {
			titleCountString = String.valueOf(numberOfSimpleConnected);
		}
		
		HTMLNode pageNode = ctx.getPageMaker().getPageNode(titleCountString + " Darknet Peers of " + node.getMyName());
		HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
		
		// FIXME! We need some nice images
		long now = System.currentTimeMillis();
		
		contentNode.addChild(core.alerts.createSummary());
	
		/* node status values */
		int bwlimitDelayTime = (int) node.getBwlimitDelayTime();
		int nodeAveragePingTime = (int) node.getNodeAveragePingTime();
		int networkSizeEstimate = node.getNetworkSizeEstimate(0);
		DecimalFormat fix4 = new DecimalFormat("0.0000");
		double missRoutingDistance =  node.missRoutingDistance.currentValue();
		DecimalFormat fix1 = new DecimalFormat("##0.0%");
		double backedoffPercent =  node.backedoffPercent.currentValue();
		String nodeUptimeString = timeIntervalToString(( now - node.startupTime ) / 1000);
		
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
			overviewList.addChild("li", "networkSizeEstimate:\u00a0" + networkSizeEstimate + "\u00a0nodes");
			overviewList.addChild("li", "nodeUptime:\u00a0" + nodeUptimeString);
			overviewList.addChild("li", "missRoutingDistance:\u00a0" + fix4.format(missRoutingDistance));
			overviewList.addChild("li", "backedoffPercent:\u00a0" + fix1.format(backedoffPercent));
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
			HTMLNode activityList = activityInfoboxContent.addChild("ul", "id", "activity");
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
			}
		}
		
		nextTableCell = advancedEnabled ? overviewTableRow.addChild("td") : overviewTableRow.addChild("td", "class", "last");
		
		// Peer statistics box
		HTMLNode peerStatsInfobox = nextTableCell.addChild("div", "class", "infobox");
		peerStatsInfobox.addChild("div", "class", "infobox-header", "Peer statistics");
		HTMLNode peerStatsContent = peerStatsInfobox.addChild("div", "class", "infobox-content");
		HTMLNode peerStatsList = peerStatsContent.addChild("ul");
		if (numberOfConnected > 0) {
			peerStatsList.addChild("li").addChild("span", "class", "peer_connected", "Connected:\u00a0" + numberOfConnected);
		}
		if (numberOfRoutingBackedOff > 0) {
			peerStatsList.addChild("li").addChild("span", "class", "peer_backedoff", (advancedEnabled ? "Backed off" : "Busy") + ":\u00a0" + numberOfRoutingBackedOff);
		}
		if (numberOfTooNew > 0) {
			peerStatsList.addChild("li").addChild("span", "class", "peer_too_new", "Too new:\u00a0" + numberOfTooNew);
		}
		if (numberOfTooOld > 0) {
			peerStatsList.addChild("li").addChild("span", "class", "peer_too_old", "Too old:\u00a0" + numberOfTooOld);
		}
		if (numberOfDisconnected > 0) {
			peerStatsList.addChild("li").addChild("span", "class", "peer_disconnected", "Disconnected:\u00a0" + numberOfDisconnected);
		}
		if (numberOfNeverConnected > 0) {
			peerStatsList.addChild("li").addChild("span", "class", "peer_never_connected", "Never Connected:\u00a0" + numberOfNeverConnected);
		}
		if (numberOfDisabled > 0) {
			peerStatsList.addChild("li").addChild("span", "class", "peer_never_connected", "Disabled:\u00a0" + numberOfDisabled); /* TODO */
		}
		if (numberOfBursting > 0) {
			peerStatsList.addChild("li").addChild("span", "class", "peer_never_connected", "Bursting:\u00a0" + numberOfBursting); /* TODO */
		}
		if (numberOfListening > 0) {
			peerStatsList.addChild("li").addChild("span", "class", "peer_never_connected", "Listening:\u00a0" + numberOfListening); /* TODO */
		}
		if (numberOfListenOnly > 0) {
			peerStatsList.addChild("li").addChild("span", "class", "peer_never_connected", "Listen Only:\u00a0" + numberOfListenOnly); /* TODO */
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
			HTMLNode peerForm = peerTableInfoboxContent.addChild("form", new String[] { "action", "method", "enctype" }, new String[] { ".", "post", "multipart/form-data" });
			peerForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "formPassword", core.formPassword });
			HTMLNode peerTable = peerForm.addChild("table", "class", "darknet_connections");
			HTMLNode peerTableHeaderRow = peerTable.addChild("tr");
			peerTableHeaderRow.addChild("th");
			peerTableHeaderRow.addChild("th", "Status");
			peerTableHeaderRow.addChild("th").addChild("span", new String[] { "title", "style" }, new String[] { "Click on the nodename link to send N2NTM", "border-bottom: 1px dotted; cursor: help;" }, "Name");
			if (advancedEnabled) {
				peerTableHeaderRow.addChild("th").addChild("span", new String[] { "title", "style" }, new String[] { "Address:Port", "border-bottom: 1px dotted; cursor: help;" }, "Address");
			}
			peerTableHeaderRow.addChild("th", "Version");
			if (advancedEnabled) {
				peerTableHeaderRow.addChild("th", "Location");
				peerTableHeaderRow.addChild("th").addChild("span", new String[] { "title", "style" }, new String[] { "Temporarily disconnected. Other node busy? Wait time(s) remaining/total", "border-bottom: 1px dotted; cursor: help;" }, "Backoff");
				
				peerTableHeaderRow.addChild("th").addChild("span", new String[] { "title", "style" }, new String[] { "Probability of the node rejecting a request due to overload or causing a timeout.", "border-bottom: 1px dotted; cursor: help;" }, "Overload Probability");
			}
			peerTableHeaderRow.addChild("th").addChild("span", new String[] { "title", "style" }, new String[] { "How long since the node connected or was last seen", "border-bottom: 1px dotted; cursor: help;" }, "Connected\u00a0/\u00a0Idle");
			
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
				if ((peerNodeStatus.getStatusValue() == Node.PEER_NODE_STATUS_CONNECTED) && (Integer.parseInt(peerNodeStatus.getSimpleVersion()) > 476)) {
					peerRow.addChild("td", "class", "peer-name").addChild("a", "href", "/send_n2ntm/?peernode_hashcode=" + peerNodeStatus.hashCode(), peerNodeStatus.getName());
				} else {
					peerRow.addChild("td", "class", "peer-name").addChild("#", peerNodeStatus.getName());
				}
				
				// address column
				if (advancedEnabled) {
					String pingTime = "";
					if (peerNodeStatus.getStatusValue() == Node.PEER_NODE_STATUS_CONNECTED ||
							peerNodeStatus.getStatusValue() == Node.PEER_NODE_STATUS_ROUTING_BACKED_OFF) {
						pingTime = " (" + (int) peerNodeStatus.getAveragePingTime() + "ms)";
					}
					peerRow.addChild("td", "class", "peer-address").addChild("#", ((peerNodeStatus.getPeerAddress() != null) ? (peerNodeStatus.getPeerAddress() + ":" + peerNodeStatus.getPeerPort()) : ("(unknown address)")) + pingTime);
				}
				
				// version column
				if (peerNodeStatus.isPublicReverseInvalidVersion()) {
					peerRow.addChild("td", "class", "peer-version").addChild("span", "class", "peer_too_new", advancedEnabled ? peerNodeStatus.getVersion() : peerNodeStatus.getSimpleVersion());
				} else {
					peerRow.addChild("td", "class", "peer-version").addChild("#", advancedEnabled ? peerNodeStatus.getVersion() : peerNodeStatus.getSimpleVersion());
				}
				
				// location column
				if (advancedEnabled) {
					peerRow.addChild("td", "class", "peer-location", String.valueOf(peerNodeStatus.getLocation()));
				}
				
				// backoff column
				if (advancedEnabled) {
					HTMLNode backoffCell = peerRow.addChild("td", "class", "peer-backoff");
					backoffCell.addChild("#", fix1.format(peerNodeStatus.getBackedOffPercent()));
					int backoff = (int) (Math.max(peerNodeStatus.getRoutingBackedOffUntil() - now, 0));
					// Don't list the backoff as zero before it's actually zero
					if ((backoff > 0) && (backoff < 1000)) {
						backoff = 1000;
					}
					backoffCell.addChild("#", " " + String.valueOf(backoff / 1000) + "/" + String.valueOf(peerNodeStatus.getRoutingBackoffLength() / 1000));
					backoffCell.addChild("#", (peerNodeStatus.getLastBackoffReason() == null) ? "" : ("/" + (peerNodeStatus.getLastBackoffReason())));
					
					HTMLNode pRejectCell = peerRow.addChild("td", "class", "peer-backoff"); // FIXME
					pRejectCell.addChild("#", fix1.format(peerNodeStatus.getPReject()));
				}
				
				// idle column
				long idle = peerNodeStatus.getTimeLastRoutable();
				if (peerNodeStatus.getStatusValue() == Node.PEER_NODE_STATUS_CONNECTED) {
					idle = peerNodeStatus.getTimeLastConnectionCompleted();
				} else if (peerNodeStatus.getStatusValue() == Node.PEER_NODE_STATUS_NEVER_CONNECTED) {
					idle = peerNodeStatus.getPeerAddedTime();
				}
				peerRow.addChild("td", "class", "peer-idle", idleToString(now, idle));
				
				if (path.endsWith("displaymessagetypes.html")) {
					HTMLNode messageCountRow = peerTable.addChild("tr", "class", "message-status");
					messageCountRow.addChild("td", "colspan", "2");
					HTMLNode messageCountCell = messageCountRow.addChild("td", "colspan", String.valueOf(advancedEnabled ? 7 : 3));
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
			
			if(!advancedEnabled) {
				peerForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "remove", "Remove selected peers" });
			} else {
				HTMLNode actionSelect = peerForm.addChild("select", "name", "action");
				actionSelect.addChild("option", "value", "", "-- Select action --");
				actionSelect.addChild("option", "value", "enable", "Enable selected peers");
				actionSelect.addChild("option", "value", "disable", "Disable selected peers");
				actionSelect.addChild("option", "value", "set_burst_only", "On selected peers, set BurstOnly");
				actionSelect.addChild("option", "value", "clear_burst_only", "On selected peers, clear BurstOnly");
				actionSelect.addChild("option", "value", "set_listen_only", "On selected peers, set ListenOnly");
				actionSelect.addChild("option", "value", "clear_listen_only", "On selected peers, clear ListenOnly");
				actionSelect.addChild("option", "value", "", "-- -- --");
				actionSelect.addChild("option", "value", "remove", "Remove selected peers");
				peerForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "submit", "Go" });
			}
		}
		// END PEER TABLE
		
		// BEGIN PEER ADDITION BOX
		HTMLNode peerAdditionInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		peerAdditionInfobox.addChild("div", "class", "infobox-header", "Add another peer");
		HTMLNode peerAdditionContent = peerAdditionInfobox.addChild("div", "class", "infobox-content");
		HTMLNode peerAdditionForm = peerAdditionContent.addChild("form", new String[] { "action", "method", "enctype" }, new String[] { ".", "post", "multipart/form-data" });
		peerAdditionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "formPassword", core.formPassword });
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
		peerAdditionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "add", "Add" });
		
		// our reference
		HTMLNode referenceInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		referenceInfobox.addChild("div", "class", "infobox-header").addChild("a", "href", "myref.txt", "My reference");
		referenceInfobox.addChild("div", "class", "infobox-content").addChild("pre", "id", "reference", node.exportPublicFieldSet().toString());
		
		StringBuffer pageBuffer = new StringBuffer();
		pageNode.generate(pageBuffer);
		this.writeReply(ctx, 200, "text/html", "OK", pageBuffer.toString());
	}
	
	public void handlePost(URI uri, Bucket data, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		if(data.size() > 1024*1024) {
			this.writeReply(ctx, 400, "text/plain", "Too big", "Too much data, darknet toadlet limited to 1MB");
			return;
		}
		
		HTTPRequest request = new HTTPRequest(uri, data, ctx);
		
		String pass = request.getPartAsString("formPassword", 32);
		if((pass == null) || !pass.equals(core.formPassword)) {
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/darknet/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			Logger.minor(this, "No password");
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
						ref.append( line ).append( "\n" );
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
				fs = new SimpleFieldSet(ref.toString(), true);
			} catch (IOException e) {
				this.sendErrorPage(ctx, 200, "Failed To Add Node", "Unable to parse the given text as a node reference. Please try again.");
				return;
			}
			PeerNode pn;
			try {
				pn = new PeerNode(fs, this.node, false);
			} catch (FSParseException e1) {
				this.sendErrorPage(ctx, 200, "Failed To Add Node", "Unable to parse the given text as a node reference. Please try again.");
				return;
			} catch (PeerParseException e1) {
				this.sendErrorPage(ctx, 200, "Failed To Add Node", "Unable to parse the given text as a node reference. Please try again.");
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
		} else if (request.isPartSet("submit") && request.getPartAsString("action",25).equals("enable")) {
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
		} else if (request.isPartSet("submit") && request.getPartAsString("action",25).equals("disable")) {
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
		} else if (request.isPartSet("submit") && request.getPartAsString("action",25).equals("set_burst_only")) {
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
		} else if (request.isPartSet("submit") && request.getPartAsString("action",25).equals("clear_burst_only")) {
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
		} else if (request.isPartSet("submit") && request.getPartAsString("action",25).equals("set_listen_only")) {
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
		} else if (request.isPartSet("submit") && request.getPartAsString("action",25).equals("clear_listen_only")) {
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
		} else if (request.isPartSet("remove") || (request.isPartSet("submit") && request.getPartAsString("action",25).equals("remove"))) {
			//int hashcode = Integer.decode(request.getParam("node")).intValue();
			
			Logger.minor(this, "Remove node");
			
			PeerNode[] peerNodes = node.getDarknetConnections();
			for(int i = 0; i < peerNodes.length; i++) {
				if (request.isPartSet("node_"+peerNodes[i].hashCode())) {
					this.node.removeDarknetConnection(peerNodes[i]);
					Logger.minor(this, "Removed node: node_"+peerNodes[i].hashCode());
				} else {
					Logger.minor(this, "Part not set: node_"+peerNodes[i].hashCode());
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
		long idleSeconds = (now - idle) / 1000;
		return timeIntervalToString( idleSeconds );
	}
	
	private String timeIntervalToString(long timeInterval) {
		StringBuffer sb = new StringBuffer(1024);
		long l = timeInterval;
		int termCount = 0;
		int weeks = (int) l / (7*24*60*60);
		if(weeks > 0) {
		  sb.append(weeks + "w");
		  termCount++;
		  l = l - (weeks * (7*24*60*60));
		}
		int days = (int) l / (24*60*60);
		if(days > 0) {
		  sb.append(days + "d");
		  termCount++;
		  l = l - (days * (24*60*60));
		}
		if(termCount >= 2) {
		  return sb.toString();
		}
		int hours = (int) l / (60*60);
		if(hours > 0) {
		  sb.append(hours + "h");
		  termCount++;
		  l = l - (hours * (60*60));
		}
		if(termCount >= 2) {
		  return sb.toString();
		}
		int minutes = (int) l / 60;
		if(minutes > 0) {
		  sb.append(minutes + "m");
		  termCount++;
		  l = l - (minutes * 60);
		}
		if(termCount >= 2) {
		  return sb.toString();
		}
		sb.append(l + "s");
		return sb.toString();
	}
}
