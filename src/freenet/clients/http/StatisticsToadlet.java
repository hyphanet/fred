package freenet.clients.http;

import java.io.IOException;
import java.net.URI;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;

import freenet.client.HighLevelSimpleClient;
import freenet.config.SubConfig;
import freenet.io.comm.IOStatisticCollector;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.NodeStarter;
import freenet.node.PeerNodeStatus;
import freenet.node.RequestStarterGroup;
import freenet.node.Version;
import freenet.support.HTMLNode;
import freenet.support.SizeUtil;
import freenet.support.TimeUtil;

public class StatisticsToadlet extends Toadlet {

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
	
	private class STMessageCount {
		public String messageName;
		public int messageCount;
		
		STMessageCount( String messageName, int messageCount ) {
			this.messageName = messageName;
			this.messageCount = messageCount;
		}
	}

	private final Node node;
	private final NodeClientCore core;
	
	protected StatisticsToadlet(Node n, NodeClientCore core, HighLevelSimpleClient client) {
		super(client);
		this.node = n;
		this.core = core;
	}

	public String supportedMethods() {
		return "GET";
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
				return firstNode.getName().compareToIgnoreCase(secondNode.getName());
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
		
		HTMLNode pageNode = ctx.getPageMaker().getPageNode("Statistics for " + node.getMyName());
		HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
		
		// FIXME! We need some nice images
		long now = System.currentTimeMillis();
	
		contentNode.addChild(core.alerts.createSummary());

		// Generate a Thread-Dump
		if(node.isUsingWrapper()){
			HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("Request a Thread Dump to be generated"));
			HTMLNode threadDumpForm = ctx.addFormChild(ctx.getPageMaker().getContentNode(infobox), "/", "threadDumpForm");
			threadDumpForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "getThreadDump", "Generate a Thread Dump" });
		}
		
		double swaps = (double)node.getSwaps();
		double noSwaps = (double)node.getNoSwaps();
		
		if(peerNodeStatuses.length>0){

			/* node status values */
			long nodeUptimeSeconds = (now - node.startupTime) / 1000;
			int bwlimitDelayTime = (int) node.getBwlimitDelayTime();
			int nodeAveragePingTime = (int) node.getNodeAveragePingTime();
			int networkSizeEstimateSession = node.getNetworkSizeEstimate(-1);
			int networkSizeEstimate24h = 0;
			int networkSizeEstimate48h = 0;
			double numberOfRemotePeerLocationsSeenInSwaps = (double)node.getNumberOfRemotePeerLocationsSeenInSwaps();
			
			if(nodeUptimeSeconds > (24*60*60)) {  // 24 hours
				networkSizeEstimate24h = node.getNetworkSizeEstimate(now - (24*60*60*1000));  // 48 hours
			}
			if(nodeUptimeSeconds > (48*60*60)) {  // 48 hours
				networkSizeEstimate48h = node.getNetworkSizeEstimate(now - (48*60*60*1000));  // 48 hours
			}
			DecimalFormat fix1p4 = new DecimalFormat("0.0000");
			DecimalFormat fix6p6 = new DecimalFormat("#####0.0#####");
			DecimalFormat fix1p6sci = new DecimalFormat("0.######E0");
			DecimalFormat fix3p1pct = new DecimalFormat("##0.0%");
            NumberFormat thousendPoint = NumberFormat.getInstance();
			double routingMissDistance =  node.routingMissDistance.currentValue();
			double backedOffPercent =  node.backedOffPercent.currentValue();
			String nodeUptimeString = TimeUtil.formatTime(nodeUptimeSeconds * 1000);  // *1000 to convert to milliseconds

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
				if(nodeUptimeSeconds > (24*60*60)) {  // 24 hours
					overviewList.addChild("li", "networkSizeEstimate24h:\u00a0" + networkSizeEstimate24h + "\u00a0nodes");
				}
				if(nodeUptimeSeconds > (48*60*60)) {  // 48 hours
					overviewList.addChild("li", "networkSizeEstimate48h:\u00a0" + networkSizeEstimate48h + "\u00a0nodes");
				}
				if ((numberOfRemotePeerLocationsSeenInSwaps > 0.0) && ((swaps > 0.0) || (noSwaps > 0.0))) {
					overviewList.addChild("li", "avrConnPeersPerNode:\u00a0" + fix6p6.format(numberOfRemotePeerLocationsSeenInSwaps/(swaps+noSwaps)) + "\u00a0peers");
				}
				overviewList.addChild("li", "nodeUptime:\u00a0" + nodeUptimeString);
				overviewList.addChild("li", "routingMissDistance:\u00a0" + fix1p4.format(routingMissDistance));
				overviewList.addChild("li", "backedOffPercent:\u00a0" + fix3p1pct.format(backedOffPercent));
				overviewList.addChild("li", "pInstantReject:\u00a0" + fix3p1pct.format(node.pRejectIncomingInstantly()));
				overviewList.addChild("li", "unclaimedFIFOSize:\u00a0" + node.getUnclaimedFIFOSize());
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
            nextTableCell = advancedEnabled ? overviewTableRow.addChild("td") : overviewTableRow.addChild("td", "class", "last");

			if(advancedEnabled) {
				
				// Peer routing backoff reason box
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
			
				//Swap statistics box
				overviewTableRow = overviewTable.addChild("tr");
				nextTableCell = overviewTableRow.addChild("td", "class", "first");
				int startedSwaps = node.getStartedSwaps();
				int swapsRejectedAlreadyLocked = node.getSwapsRejectedAlreadyLocked();
				int swapsRejectedNowhereToGo = node.getSwapsRejectedNowhereToGo();
				int swapsRejectedRateLimit = node.getSwapsRejectedRateLimit();
				int swapsRejectedLoop = node.getSwapsRejectedLoop();
				int swapsRejectedRecognizedID = node.getSwapsRejectedRecognizedID();
				double locChangeSession = node.getLocationChangeSession();
				
				HTMLNode locationSwapInfobox = nextTableCell.addChild("div", "class", "infobox");
				locationSwapInfobox.addChild("div", "class", "infobox-header", "Location swaps");
				HTMLNode locationSwapInfoboxContent = locationSwapInfobox.addChild("div", "class", "infobox-content");
				HTMLNode locationSwapList = locationSwapInfoboxContent.addChild("ul");
				if (swaps > 0.0) {
					locationSwapList.addChild("li", "locChangeSession:\u00a0" + fix1p6sci.format(locChangeSession));
					locationSwapList.addChild("li", "locChangePerSwap:\u00a0" + fix1p6sci.format(locChangeSession/swaps));
				}
				if ((swaps > 0.0) && (nodeUptimeSeconds >= 60)) {
					locationSwapList.addChild("li", "locChangePerMinute:\u00a0" + fix1p6sci.format(locChangeSession/(double)(nodeUptimeSeconds/60.0)));
				}
				if ((swaps > 0.0) && (nodeUptimeSeconds >= 60)) {
					locationSwapList.addChild("li", "swapsPerMinute:\u00a0" + fix1p6sci.format(swaps/(double)(nodeUptimeSeconds/60.0)));
				}
				if ((noSwaps > 0.0) && (nodeUptimeSeconds >= 60)) {
					locationSwapList.addChild("li", "noSwapsPerMinute:\u00a0" + fix1p6sci.format(noSwaps/(double)(nodeUptimeSeconds/60.0)));
				}
				if ((swaps > 0.0) && (noSwaps > 0.0)) {
					locationSwapList.addChild("li", "swapsPerNoSwaps:\u00a0" + fix1p6sci.format(swaps/noSwaps));
				}
				if (swaps > 0.0) {
					locationSwapList.addChild("li", "swaps:\u00a0" + (int)swaps);
				}
				if (noSwaps > 0.0) {
					locationSwapList.addChild("li", "noSwaps:\u00a0" + (int)noSwaps);
				}
				if (startedSwaps > 0) {
					locationSwapList.addChild("li", "startedSwaps:\u00a0" + startedSwaps);
				}
				if (swapsRejectedAlreadyLocked > 0) {
					locationSwapList.addChild("li", "swapsRejectedAlreadyLocked:\u00a0" + swapsRejectedAlreadyLocked);
				}
				if (swapsRejectedNowhereToGo > 0) {
					locationSwapList.addChild("li", "swapsRejectedNowhereToGo:\u00a0" + swapsRejectedNowhereToGo);
				}
				if (swapsRejectedRateLimit > 0) {
					locationSwapList.addChild("li", "swapsRejectedRateLimit:\u00a0" + swapsRejectedRateLimit);
				}
				if (swapsRejectedLoop > 0) {
					locationSwapList.addChild("li", "swapsRejectedLoop:\u00a0" + swapsRejectedLoop);
				}
				if (swapsRejectedRecognizedID > 0) {
					locationSwapList.addChild("li", "swapsRejectedRecognizedID:\u00a0" + swapsRejectedRecognizedID);
				}
				nextTableCell = overviewTableRow.addChild("td");
			
				// Bandwidth box
				HTMLNode bandwidthInfobox = nextTableCell.addChild("div", "class", "infobox");
				bandwidthInfobox.addChild("div", "class", "infobox-header", "Bandwidth");
				HTMLNode bandwidthInfoboxContent = bandwidthInfobox.addChild("div", "class", "infobox-content");
				HTMLNode bandwidthList = bandwidthInfoboxContent.addChild("ul");
				long[] total = IOStatisticCollector.getTotalIO();
				long total_output_rate = (total[0]) / nodeUptimeSeconds;
				long total_input_rate = (total[1]) / nodeUptimeSeconds;
				long totalPayload = node.getTotalPayloadSent();
				long total_payload_rate = totalPayload / nodeUptimeSeconds;
				int percent = (int) (100 * totalPayload / total[0]);
				bandwidthList.addChild("li", "Total Output:\u00a0" + SizeUtil.formatSize(total[0]) + " (" + SizeUtil.formatSize(total_output_rate, true) + "ps)");
				bandwidthList.addChild("li", "Payload Output:\u00a0" + SizeUtil.formatSize(totalPayload) + " (" + SizeUtil.formatSize(total_payload_rate, true) + "ps) ("+percent+"%)");
				bandwidthList.addChild("li", "Total Input:\u00a0" + SizeUtil.formatSize(total[1]) + " (" + SizeUtil.formatSize(total_input_rate, true) + "ps)");
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
				bandwidthList.addChild("li", "Output Rate:\u00a0" + SizeUtil.formatSize(output_rate, true) + "ps (of\u00a0"+SizeUtil.formatSize(outputBandwidthLimit, true)+"ps)");
				bandwidthList.addChild("li", "Input Rate:\u00a0" + SizeUtil.formatSize(input_rate, true) + "ps (of\u00a0"+SizeUtil.formatSize(inputBandwidthLimit, true)+"ps)");
                nextTableCell = overviewTableRow.addChild("td");

                // store size box
                HTMLNode storeSizeInfobox = nextTableCell.addChild("div", "class", "infobox");
                storeSizeInfobox.addChild("div", "class", "infobox-header", "Store size");
                HTMLNode storeSizeInfoboxContent = storeSizeInfobox.addChild("div", "class", "infobox-content");
                HTMLNode storeSizeList = storeSizeInfoboxContent.addChild("ul");
                
                final long fix32kb = 32 * 1024;
                
                long cachedKeys = node.getChkDatacache().keyCount();
                long cachedSize = cachedKeys * fix32kb;
                long storeKeys = node.getChkDatastore().keyCount();
                long storeSize = storeKeys * fix32kb;
                long overallKeys = cachedKeys + storeKeys;
                long overallSize = cachedSize + storeSize;
                
//                long maxCachedKeys = node.getChkDatacache().getMaxKeys();
//                long maxStoreKeys = node.getChkDatastore().getMaxKeys();
                long maxOverallKeys = node.getMaxTotalKeys();
                long maxOverallSize = maxOverallKeys * fix32kb;
                
                long cachedStoreHits = node.getChkDatacache().hits();
                long cachedStoreMisses = node.getChkDatacache().misses();
                long cacheAccesses = cachedStoreHits + cachedStoreMisses;
                long storeHits = node.getChkDatastore().hits();
                long storeMisses = node.getChkDatastore().misses();
                long storeAccesses = storeHits + storeMisses;
                long overallAccesses = storeAccesses + cacheAccesses;
                
                storeSizeList.addChild("li", 
                        "Cached keys:\u00a0" + thousendPoint.format(cachedKeys) + 
                        " (" + SizeUtil.formatSize(cachedSize, true) + ')');

                storeSizeList.addChild("li", 
                        "Stored keys:\u00a0" + thousendPoint.format(storeKeys) + 
                        " (" + SizeUtil.formatSize(storeSize, true) + ')');

                storeSizeList.addChild("li", 
                        "Overall size:\u00a0" + thousendPoint.format(overallKeys) + 
                        "\u00a0/\u00a0" + thousendPoint.format(maxOverallKeys) +
                        " (" + SizeUtil.formatSize(overallSize, true) + 
                        "\u00a0/\u00a0" + SizeUtil.formatSize(maxOverallSize, true) + 
                        ")\u00a0(" + ((overallKeys*100)/maxOverallKeys) + "%)");

                storeSizeList.addChild("li", 
                        "Cache hits:\u00a0" + thousendPoint.format(cachedStoreHits) + 
                        "\u00a0/\u00a0"+thousendPoint.format(cacheAccesses) +
                        "\u00a0(" + ((cachedStoreHits*100) / (cacheAccesses)) + "%)");
                
                storeSizeList.addChild("li", 
                        "Store hits:\u00a0" + thousendPoint.format(storeHits) + 
                        "\u00a0/\u00a0"+thousendPoint.format(storeAccesses) +
                        "\u00a0(" + ((storeHits*100) / (storeAccesses)) + "%)");

                storeSizeList.addChild("li", 
                        "Avg. access rate:\u00a0" + thousendPoint.format(overallAccesses/nodeUptimeSeconds) + "/s");
            
                nextTableCell = advancedEnabled ? overviewTableRow.addChild("td") : overviewTableRow.addChild("td", "class", "last");

                // jvm stats box
                HTMLNode jvmStatsInfobox = nextTableCell.addChild("div", "class", "infobox");
                jvmStatsInfobox.addChild("div", "class", "infobox-header", "JVM info");
                HTMLNode jvmStatsInfoboxContent = jvmStatsInfobox.addChild("div", "class", "infobox-content");
                HTMLNode jvmStatsList = jvmStatsInfoboxContent.addChild("ul");

                Runtime rt = Runtime.getRuntime();
                float freeMemory = (float) rt.freeMemory();
                float totalMemory = (float) rt.totalMemory();
                float maxMemory = (float) rt.maxMemory();

                long usedJavaMem = (long)(totalMemory - freeMemory);
                long allocatedJavaMem = (long)totalMemory;
                long maxJavaMem = (long)maxMemory;
                int availableCpus = rt.availableProcessors();
                
        		ThreadGroup tg = Thread.currentThread().getThreadGroup();
        		while(tg.getParent() != null) tg = tg.getParent();
        		int threadCount = tg.activeCount();

                jvmStatsList.addChild("li", "Used Java memory:\u00a0" + SizeUtil.formatSize(usedJavaMem, true));
                jvmStatsList.addChild("li", "Allocated Java memory:\u00a0" + SizeUtil.formatSize(allocatedJavaMem, true));
                jvmStatsList.addChild("li", "Maximum Java memory:\u00a0" + SizeUtil.formatSize(maxJavaMem, true));
                jvmStatsList.addChild("li", "Available CPUs:\u00a0" + availableCpus);
                jvmStatsList.addChild("li", "Running threads:\u00a0" + thousendPoint.format(threadCount));
			
                // unclaimedFIFOMessageCounts box
				overviewTableRow = overviewTable.addChild("tr");
				nextTableCell = overviewTableRow.addChild("td", "class", "first");
				Map unclaimedFIFOMessageCountsMap = node.getUSM().getUnclaimedFIFOMessageCounts();
				STMessageCount[] unclaimedFIFOMessageCountsArray = new STMessageCount[unclaimedFIFOMessageCountsMap.size()];
				int i = 0;
				int totalCount = 0;
				for (Iterator messageCounts = unclaimedFIFOMessageCountsMap.keySet().iterator(); messageCounts.hasNext(); ) {
					String messageName = (String) messageCounts.next();
					int messageCount = ((Integer) unclaimedFIFOMessageCountsMap.get(messageName)).intValue();
					totalCount = totalCount + messageCount;
					unclaimedFIFOMessageCountsArray[i++] = new STMessageCount( messageName, messageCount );
				}
				Arrays.sort(unclaimedFIFOMessageCountsArray, new Comparator() {
					public int compare(Object first, Object second) {
						STMessageCount firstCount = (STMessageCount) first;
						STMessageCount secondCount = (STMessageCount) second;
						return secondCount.messageCount - firstCount.messageCount;  // sort in descending order
					}
				});
				HTMLNode unclaimedFIFOMessageCountsInfobox = nextTableCell.addChild("div", "class", "infobox");
				unclaimedFIFOMessageCountsInfobox.addChild("div", "class", "infobox-header", "unclaimedFIFO Message Counts");
				HTMLNode unclaimedFIFOMessageCountsInfoboxContent = unclaimedFIFOMessageCountsInfobox.addChild("div", "class", "infobox-content");
				HTMLNode unclaimedFIFOMessageCountsList = unclaimedFIFOMessageCountsInfoboxContent.addChild("ul");
				for (int countsArrayIndex = 0, countsArrayCount = unclaimedFIFOMessageCountsArray.length; countsArrayIndex < countsArrayCount; countsArrayIndex++) {
					STMessageCount messageCountItem = (STMessageCount) unclaimedFIFOMessageCountsArray[countsArrayIndex];
					int thisMessageCount = messageCountItem.messageCount;
					double thisMessagePercentOfTotal = ((double) thisMessageCount) / ((double) totalCount);
					unclaimedFIFOMessageCountsList.addChild("li", "" + messageCountItem.messageName + ":\u00a0" + thisMessageCount + "\u00a0(" + fix3p1pct.format(thisMessagePercentOfTotal) + ')');
				}
				unclaimedFIFOMessageCountsList.addChild("li", "Unclaimed Messages Considered:\u00a0" + totalCount);
				nextTableCell = overviewTableRow.addChild("td");
			
				// Load balancing box
				// Include overall window, and RTTs for each
				RequestStarterGroup starters = core.requestStarters;
				double window = starters.getWindow();
				HTMLNode loadStatsInfobox = nextTableCell.addChild("div", "class", "infobox");
				loadStatsInfobox.addChild("div", "class", "infobox-header", "Load limiting");
				HTMLNode loadStatsContent = loadStatsInfobox.addChild("div", "class", "infobox-content");
				HTMLNode loadStatsList = loadStatsContent.addChild("ul");
				loadStatsList.addChild("ul", "Global window: "+window);
				loadStatsList.addChild("ul", starters.statsPageLine(false, false));
				loadStatsList.addChild("ul", starters.statsPageLine(true, false));
				loadStatsList.addChild("ul", starters.statsPageLine(false, true));
				loadStatsList.addChild("ul", starters.statsPageLine(true, true));
				nextTableCell = overviewTableRow.addChild("td");

				// node version information box
                HTMLNode versionInfobox = nextTableCell.addChild("div", "class", "infobox");
                versionInfobox.addChild("div", "class", "infobox-header", "Node Version Information");
                HTMLNode versionInfoboxContent = versionInfobox.addChild("div", "class", "infobox-content");
                HTMLNode versionInfoboxList = versionInfoboxContent.addChild("ul");
				versionInfoboxList.addChild("li", "Freenet " + Version.nodeVersion + " Build #" + Version.buildNumber() + " r" + Version.cvsRevision);
				if(NodeStarter.extBuildNumber < NodeStarter.RECOMMENDED_EXT_BUILD_NUMBER) {
					versionInfoboxList.addChild("li", "Freenet-ext Build #" + NodeStarter.extBuildNumber + '(' + NodeStarter.RECOMMENDED_EXT_BUILD_NUMBER + ") r" + NodeStarter.extRevisionNumber);
				} else {
					versionInfoboxList.addChild("li", "Freenet-ext Build #" + NodeStarter.extBuildNumber + " r" + NodeStarter.extRevisionNumber);
				}
				versionInfoboxList.addChild("li", "JVM Vendor:\u00a0" + System.getProperty("java.vm.vendor"));
				versionInfoboxList.addChild("li", "JVM Version:\u00a0" + System.getProperty("java.vm.version"));
				versionInfoboxList.addChild("li", "OS Name:\u00a0" + System.getProperty("os.name"));
				versionInfoboxList.addChild("li", "OS Version:\u00a0" + System.getProperty("os.version"));
				versionInfoboxList.addChild("li", "OS Architecture:\u00a0" + System.getProperty("os.arch"));
			}
		}

		this.writeReply(ctx, 200, "text/html", "OK", pageNode.generate());
	}
}
