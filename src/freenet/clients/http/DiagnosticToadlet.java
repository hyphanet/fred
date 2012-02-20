package freenet.clients.http;

import java.io.IOException;
import java.net.URI;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import freenet.client.async.ClientRequester;
import freenet.client.HighLevelSimpleClient;
import freenet.config.SubConfig;
import freenet.io.comm.IncomingPacketFilterImpl;
import freenet.io.xfer.BlockReceiver;
import freenet.io.xfer.BlockTransmitter;
import freenet.l10n.NodeL10n;
import freenet.keys.FreenetURI;
import freenet.node.Location;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.NodeStarter;
import freenet.node.NodeStats;
import freenet.node.OpennetManager;
import freenet.node.PeerManager;
import freenet.node.PeerNodeStatus;
import freenet.node.RequestClient;
import freenet.node.RequestStarterGroup;
import freenet.node.Version;
import freenet.node.stats.DataStoreInstanceType;
import freenet.node.stats.DataStoreStats;
import freenet.node.stats.StatsNotAvailableException;
import freenet.node.stats.StoreAccessStats;
import freenet.support.BandwidthStatsContainer;
import freenet.support.HTMLNode;
import freenet.support.SizeUtil;
import freenet.support.TimeUtil;
import freenet.support.api.HTTPRequest;
import freenet.support.io.NativeThread;
import freenet.pluginmanager.PluginManager.PluginProgress;

public class DiagnosticToadlet extends Toadlet {

	static final NumberFormat thousandPoint = NumberFormat.getInstance();

	private static class STMessageCount {
		public String messageName;
		public int messageCount;

		STMessageCount( String messageName, int messageCount ) {
			this.messageName = messageName;
			this.messageCount = messageCount;
		}
	}

	private final Node node;
	private final NodeClientCore core;
	private final NodeStats stats;
	private final PeerManager peers;
	private final DecimalFormat fix1p1 = new DecimalFormat("0.0");
	private final DecimalFormat fix1p2 = new DecimalFormat("0.00");
	private final DecimalFormat fix1p4 = new DecimalFormat("0.0000");
	private final DecimalFormat fix1p6sci = new DecimalFormat("0.######E0");
	private final DecimalFormat fix3p1pct = new DecimalFormat("##0.0%");
	private final DecimalFormat fix3p1US = new DecimalFormat("##0.0", new DecimalFormatSymbols(Locale.US));
	private final DecimalFormat fix3pctUS = new DecimalFormat("##0%", new DecimalFormatSymbols(Locale.US));
	private final DecimalFormat fix6p6 = new DecimalFormat("#####0.0#####");

	final FCPServer fcp;

	protected DiagnosticToadlet(Node n, NodeClientCore core, HighLevelSimpleClient client) {
		super(client);
		this.node = n;
		this.core = core;
		stats = node.nodeStats;
		peers = node.peers;
	}

	public void handleMethodGET(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {

		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, NodeL10n.ENGLISH.getString("Toadlet.unauthorizedTitle"), NodeL10n.ENGLISH.getString("Toadlet.unauthorized"));
			return;
		}

		final SubConfig nodeConfig = node.config.get("node");
		
		final String requestPath = request.getPath().substring(path().length());

		final String text;

		// drawNodeVersionBox
		text += NodeL10n.ENGLISH.getString("WelcomeToadlet.version", new String[] { "fullVersion", "build", "rev" },
				new String[] { Version.publicVersion(), Integer.toString(Version.buildNumber()), Version.cvsRevision() }) + "\n";
		if(NodeStarter.extBuildNumber < NodeStarter.RECOMMENDED_EXT_BUILD_NUMBER)
			text += NodeL10n.ENGLISH.getString("WelcomeToadlet.extVersionWithRecommended", 
					new String[] { "build", "recbuild", "rev" }, 
					new String[] { Integer.toString(NodeStarter.extBuildNumber), Integer.toString(NodeStarter.RECOMMENDED_EXT_BUILD_NUMBER), NodeStarter.extRevisionNumber });
		else
			text += NodeL10n.ENGLISH.getString("WelcomeToadlet.extVersion", new String[] { "build", "rev" },
					new String[] { Integer.toString(NodeStarter.extBuildNumber), NodeStarter.extRevisionNumber });
		text += "\n\n";

		// drawNodeVersionBox
		Runtime rt = Runtime.getRuntime();
		long freeMemory = rt.freeMemory();
		long totalMemory = rt.totalMemory();
		long maxMemory = rt.maxMemory();
		long usedJavaMem = totalMemory - freeMemory;
		long allocatedJavaMem = totalMemory;
		long maxJavaMem = maxMemory;
		int availableCpus = rt.availableProcessors();
		int threadCount = stats.getActiveThreadCount();
		text += l10n("usedMemory", "memory", SizeUtil.formatSize(usedJavaMem, true)) + "\n";
		text += l10n("allocMemory", "memory", SizeUtil.formatSize(allocatedJavaMem, true) + "\n";
		text += l10n("maxMemory", "memory", SizeUtil.formatSize(maxJavaMem, true))) + "\n";
		text += l10n("threads", new String[] { "running", "max" },
				new String[] { thousandPoint.format(threadCount), Integer.toString(stats.getThreadLimit()) }) + "\n";
		text += l10n("cpus", "count", Integer.toString(availableCpus)) + "\n";
		text += l10n("javaVersion", "version", System.getProperty("java.version")) + "\n";
		text += l10n("jvmVendor", "vendor", System.getProperty("java.vendor")) + "\n";
		text += l10n("jvmName", "name", System.getProperty("java.vm.name")) + "\n";
		text += l10n("jvmVersion", "version", System.getProperty("java.vm.version")) + "\n";
		text += l10n("osName", "name", System.getProperty("os.name")) + "\n";
		text += l10n("osVersion", "version", System.getProperty("os.version")) + "\n";
		text += l10n("osArch", "arch", System.getProperty("os.arch")) + "\n";

		// drawActivity
		int numLocalCHKInserts = node.getNumLocalCHKInserts();
		int numRemoteCHKInserts = node.getNumRemoteCHKInserts();
		int numLocalSSKInserts = node.getNumLocalSSKInserts();
		int numRemoteSSKInserts = node.getNumRemoteSSKInserts();
		int numLocalCHKRequests = node.getNumLocalCHKRequests();
		int numRemoteCHKRequests = node.getNumRemoteCHKRequests();
		int numLocalSSKRequests = node.getNumLocalSSKRequests();
		int numRemoteSSKRequests = node.getNumRemoteSSKRequests();
		int numTransferringRequests = node.getNumTransferringRequestSenders();
		int numTransferringRequestHandlers = node.getNumTransferringRequestHandlers();
		int numCHKOfferReplys = node.getNumCHKOfferReplies();
		int numSSKOfferReplys = node.getNumSSKOfferReplies();
		int numCHKRequests = numLocalCHKRequests + numRemoteCHKRequests;
		int numSSKRequests = numLocalSSKRequests + numRemoteSSKRequests;
		int numCHKInserts = numLocalCHKInserts + numRemoteCHKInserts;
		int numSSKInserts = numLocalSSKInserts + numRemoteSSKInserts;
		if ((numTransferringRequests == 0) &&
				(numCHKRequests == 0) && (numSSKRequests == 0) &&
				(numCHKInserts == 0) && (numSSKInserts == 0) &&
				(numTransferringRequestHandlers == 0) && 
				(numCHKOfferReplys == 0) && (numSSKOfferReplys == 0)) {
			text += l10n("noRequests") + "\n";
		} else {
			if (numCHKInserts > 0 || numSSKInserts > 0) {
				text += l10n("activityInserts", 
						new String[] { "CHKhandlers", "SSKhandlers", "local" } , 
						new String[] { Integer.toString(numCHKInserts), Integer.toString(numSSKInserts), Integer.toString(numLocalCHKInserts)+"/" + Integer.toString(numLocalSSKInserts)})
						+ "\n";
			}
			if (numCHKRequests > 0 || numSSKRequests > 0) {
				text += l10n("activityRequests", 
						new String[] { "CHKhandlers", "SSKhandlers", "local" } , 
						new String[] { Integer.toString(numCHKRequests), Integer.toString(numSSKRequests), Integer.toString(numLocalCHKRequests)+"/" + Integer.toString(numLocalSSKRequests)})
						+ "\n";
			}
			if (numTransferringRequests > 0 || numTransferringRequestHandlers > 0) {
				text += l10n("transferringRequests", 
						new String[] { "senders", "receivers", "turtles" }, new String[] { Integer.toString(numTransferringRequests), Integer.toString(numTransferringRequestHandlers), "0"})
						+ "\n";
			}
			if (numCHKOfferReplys > 0 || numSSKOfferReplys > 0) {
				text += l10n("offerReplys", 
						new String[] { "chk", "ssk" }, new String[] { Integer.toString(numCHKOfferReplys), Integer.toString(numSSKOfferReplys) })
						+ "\n";
			}
			text += l10n("runningBlockTransfers", 
					new String[] { "sends", "receives" }, new String[] { Integer.toString(BlockTransmitter.getRunningSends()), Integer.toString(BlockReceiver.getRunningReceives()) })
					+ "\n";
		}

		// drawPeerStatsBox
		if (numberOfConnected > 0)
			text += l10nDark("connectedShort") + ": " + numberOfConnected;
		if (numberOfRoutingBackedOff > 0)
			text += l10nDark((advancedModeEnabled ? "backedOff" : "busy")+"Short") + ": " + numberOfRoutingBackedOff + "\n";
		if (numberOfTooNew > 0)
			text += l10nDark("tooNewShort") + ": " + numberOfTooNew + "\n";
		if (numberOfTooOld > 0)
			text += l10nDark("tooOldShort") + ": " + numberOfTooOld + "\n";
		if (numberOfDisconnected > 0)
			text += l10nDark("notConnectedShort") + ": " + numberOfDisconnected + "\n";
		if (numberOfNeverConnected > 0)
			text += l10nDark("neverConnectedShort") + ": " + numberOfNeverConnected + "\n";
		if (numberOfDisabled > 0)
			text += l10nDark("disabledShort") + ": " + numberOfDisabled + "\n";
		if (numberOfBursting > 0)
			text += l10nDark("burstingShort") + ": " + numberOfBursting + "\n";
		if (numberOfListening > 0)
			text += l10nDark("listeningShort") + ": " + numberOfListening + "\n";
		if (numberOfListenOnly > 0)
			text += l10nDark("listenOnlyShort") + ": " + numberOfListenOnly + "\n";
		if (numberOfClockProblem > 0)
			text += l10nDark("clockProblemShort") + ": " + numberOfClockProblem + "\n";
		if (numberOfConnError > 0)
			text += l10nDark("connErrorShort") + ": " + numberOfConnError + "\n";
		if (numberOfDisconnecting > 0)
			text += l10nDark("disconnectingShort") + ": " + numberOfDisconnecting + "\n";
		if (numberOfSeedServers > 0)
			text += l10nDark("seedServersShort") + ": " + numberOfSeedServers + "\n";
		if (numberOfSeedClients > 0)
			text += l10nDark("seedClientsShort") + ": " + numberOfSeedClients + "\n";
		if (numberOfRoutingDisabled > 0)
			text += l10nDark("routingDisabledShort") + ": " + numberOfRoutingDisabled + "\n";
		if (numberOfNoLoadStats > 0)
			text += l10nDark("noLoadStatsShort") + ": " + numberOfNoLoadStats + "\n";
		OpennetManager om = node.getOpennet();
		if(om != null) {
			text += l10n("maxTotalPeers")+": "+om.getNumberOfConnectedPeersToAimIncludingDarknet() + "\n";
			text += l10n("maxOpennetPeers")+": "+om.getNumberOfConnectedPeersToAim() + "\n";
		}

		// drawBandwidth
		long[] total = node.collector.getTotalIO();
		if(total[0] == 0 || total[1] == 0)
			return;
		long total_output_rate = (total[0]) / nodeUptimeSeconds;
		long total_input_rate = (total[1]) / nodeUptimeSeconds;
		long totalPayload = node.getTotalPayloadSent();
		long total_payload_rate = totalPayload / nodeUptimeSeconds;
		if(node.clientCore == null) throw new NullPointerException();
		BandwidthStatsContainer stats = node.clientCore.bandwidthStatsPutter.getLatestBWData();
		if(stats == null) throw new NullPointerException();
		long overall_total_out = stats.totalBytesOut;
		long overall_total_in = stats.totalBytesIn;
		int percent = (int) (100 * totalPayload / total[0]);
		long[] rate = node.nodeStats.getNodeIOStats();
		long delta = (rate[5] - rate[2]) / 1000;
		if(delta > 0) {
			long output_rate = (rate[3] - rate[0]) / delta;
			long input_rate = (rate[4] - rate[1]) / delta;
			SubConfig nodeConfig = node.config.get("node");
			int outputBandwidthLimit = nodeConfig.getInt("outputBandwidthLimit");
			int inputBandwidthLimit = nodeConfig.getInt("inputBandwidthLimit");
			if(inputBandwidthLimit == -1) {
				inputBandwidthLimit = outputBandwidthLimit * 4;
			}
			text += l10n("inputRate", new String[] { "rate", "max" }, new String[] { SizeUtil.formatSize(input_rate, true), SizeUtil.formatSize(inputBandwidthLimit, true) }) + "\n";
			text += l10n("outputRate", new String[] { "rate", "max" }, new String[] { SizeUtil.formatSize(output_rate, true), SizeUtil.formatSize(outputBandwidthLimit, true) }) + "\n";
		}
		text += l10n("totalInputSession", new String[] { "total", "rate" }, new String[] { SizeUtil.formatSize(total[1], true), SizeUtil.formatSize(total_input_rate, true) }) + "\n";
		text += l10n("totalOutputSession", new String[] { "total", "rate" }, new String[] { SizeUtil.formatSize(total[0], true), SizeUtil.formatSize(total_output_rate, true) } ) + "\n";
		text += l10n("payloadOutput", new String[] { "total", "rate", "percent" }, new String[] { SizeUtil.formatSize(totalPayload, true), SizeUtil.formatSize(total_payload_rate, true), Integer.toString(percent) } ) + "\n";
		text += l10n("totalInput", new String[] { "total" }, new String[] { SizeUtil.formatSize(overall_total_in, true) }) + "\n";
		text += l10n("totalOutput", new String[] { "total" }, new String[] { SizeUtil.formatSize(overall_total_out, true) } ) + "\n";
		long totalBytesSentCHKRequests = node.nodeStats.getCHKRequestTotalBytesSent();
		long totalBytesSentSSKRequests = node.nodeStats.getSSKRequestTotalBytesSent();
		long totalBytesSentCHKInserts = node.nodeStats.getCHKInsertTotalBytesSent();
		long totalBytesSentSSKInserts = node.nodeStats.getSSKInsertTotalBytesSent();
		long totalBytesSentOfferedKeys = node.nodeStats.getOfferedKeysTotalBytesSent();
		long totalBytesSendOffers = node.nodeStats.getOffersSentBytesSent();
		long totalBytesSentSwapOutput = node.nodeStats.getSwappingTotalBytesSent();
		long totalBytesSentAuth = node.nodeStats.getTotalAuthBytesSent();
		long totalBytesSentAckOnly = node.nodeStats.getNotificationOnlyPacketsSentBytes();
		long totalBytesSentResends = node.nodeStats.getResendBytesSent();
		long totalBytesSentUOM = node.nodeStats.getUOMBytesSent();
		long totalBytesSentAnnounce = node.nodeStats.getAnnounceBytesSent();
		long totalBytesSentAnnouncePayload = node.nodeStats.getAnnounceBytesPayloadSent();
		long totalBytesSentRoutingStatus = node.nodeStats.getRoutingStatusBytes();
		long totalBytesSentNetworkColoring = node.nodeStats.getNetworkColoringSentBytes();
		long totalBytesSentPing = node.nodeStats.getPingSentBytes();
		long totalBytesSentProbeRequest = node.nodeStats.getProbeRequestSentBytes();
		long totalBytesSentRouted = node.nodeStats.getRoutedMessageSentBytes();
		long totalBytesSentDisconn = node.nodeStats.getDisconnBytesSent();
		long totalBytesSentInitial = node.nodeStats.getInitialMessagesBytesSent();
		long totalBytesSentChangedIP = node.nodeStats.getChangedIPBytesSent();
		long totalBytesSentNodeToNode = node.nodeStats.getNodeToNodeBytesSent();
		long totalBytesSentAllocationNotices = node.nodeStats.getAllocationNoticesBytesSent();
		long totalBytesSentFOAF = node.nodeStats.getFOAFBytesSent();
		long totalBytesSentRemaining = total[0] - 
			(totalPayload + totalBytesSentCHKRequests + totalBytesSentSSKRequests +
			totalBytesSentCHKInserts + totalBytesSentSSKInserts +
			totalBytesSentOfferedKeys + totalBytesSendOffers + totalBytesSentSwapOutput + 
			totalBytesSentAuth + totalBytesSentAckOnly + totalBytesSentResends +
			totalBytesSentUOM + totalBytesSentAnnounce + 
			totalBytesSentRoutingStatus + totalBytesSentNetworkColoring + totalBytesSentPing +
			totalBytesSentProbeRequest + totalBytesSentRouted + totalBytesSentDisconn + 
			totalBytesSentInitial + totalBytesSentChangedIP + totalBytesSentNodeToNode + totalBytesSentAllocationNotices + totalBytesSentFOAF);
		text += l10n("requestOutput", new String[] { "chk", "ssk" }, new String[] { SizeUtil.formatSize(totalBytesSentCHKRequests, true), SizeUtil.formatSize(totalBytesSentSSKRequests, true) }) + "\n";
		text += l10n("insertOutput", new String[] { "chk", "ssk" }, new String[] { SizeUtil.formatSize(totalBytesSentCHKInserts, true), SizeUtil.formatSize(totalBytesSentSSKInserts, true) }) + "\n";
		text += l10n("offeredKeyOutput", new String[] { "total", "offered" }, new String[] { SizeUtil.formatSize(totalBytesSentOfferedKeys, true), SizeUtil.formatSize(totalBytesSendOffers, true) }) + "\n";
		text += l10n("swapOutput", "total", SizeUtil.formatSize(totalBytesSentSwapOutput, true)) + "\n";
		text += l10n("authBytes", "total", SizeUtil.formatSize(totalBytesSentAuth, true)) + "\n";
		text += l10n("ackOnlyBytes", "total", SizeUtil.formatSize(totalBytesSentAckOnly, true)) + "\n";
		text += l10n("resendBytes", new String[] { "total", "percent" }, new String[] { SizeUtil.formatSize(totalBytesSentResends, true), Long.toString((100 * totalBytesSentResends) / Math.max(1, total[0])) } ) + "\n";
		text += l10n("uomBytes", "total",  SizeUtil.formatSize(totalBytesSentUOM, true)) + "\n";
		text += l10n("announceBytes", new String[] { "total", "payload" }, new String[] { SizeUtil.formatSize(totalBytesSentAnnounce, true), SizeUtil.formatSize(totalBytesSentAnnouncePayload, true) }) + "\n";
		text += l10n("adminBytes", new String[] { "routingStatus", "disconn", "initial", "changedIP" }, new String[] { SizeUtil.formatSize(totalBytesSentRoutingStatus, true), SizeUtil.formatSize(totalBytesSentDisconn, true), SizeUtil.formatSize(totalBytesSentInitial, true), SizeUtil.formatSize(totalBytesSentChangedIP, true) }) + "\n";
		text += l10n("debuggingBytes", new String[] { "netColoring", "ping", "probe", "routed" }, new String[] { SizeUtil.formatSize(totalBytesSentNetworkColoring, true), SizeUtil.formatSize(totalBytesSentPing, true), SizeUtil.formatSize(totalBytesSentProbeRequest, true), SizeUtil.formatSize(totalBytesSentRouted, true) } ) + "\n";
		text += l10n("nodeToNodeBytes", "total", SizeUtil.formatSize(totalBytesSentNodeToNode, true)) + "\n";
		text += l10n("loadAllocationNoticesBytes", "total", SizeUtil.formatSize(totalBytesSentAllocationNotices, true)) + "\n";
		text += l10n("foafBytes", "total", SizeUtil.formatSize(totalBytesSentFOAF, true)) + "\n";
		text += l10n("unaccountedBytes", new String[] { "total", "percent" },
				new String[] { SizeUtil.formatSize(totalBytesSentRemaining, true), Integer.toString((int)(totalBytesSentRemaining*100 / total[0])) }) + "\n";
		double sentOverheadPerSecond = node.nodeStats.getSentOverheadPerSecond();
		text += l10n("totalOverhead", new String[] { "rate", "percent" }, 
				new String[] { SizeUtil.formatSize((long)sentOverheadPerSecond), Integer.toString((int)((100 * sentOverheadPerSecond) / total_output_rate)) }) + "\n";

		// showStartingPlugins
		PluginManager pm = node.pluginManager;
		if (!pm.getPlugins().isEmpty()) {
			text += NodeL10n.getBase().getString("PluginToadlet.pluginListTitle") + "\n";
			Iterator<PluginInfoWrapper> it = pm.getPlugins().iterator();
			while (it.hasNext()) {
				PluginInfoWrapper pi = it.next();
				long ver = pi.getPluginLongVersion();
				if (ver != -1)
					text += pi.getFilename() + " (" + pi.getPluginClassName() + ") - "  + pi.getPluginVersion()+ " ("+ver+")" + " " + pi.getThreadName() "\n";
				else
					text += pi.getFilename() + " (" + pi.getPluginClassName() + ") - " + pi.getPluginVersion() + " " + pi.getThreadName() + "\n";
			}
		}

		// handleGetInner
		RequestStatus[] reqs = fcp.getGlobalRequests();
		if(reqs.length < 1)
			text += NodeL10n.ENGLISH.getText("QueueToadlet.globalQueueIsEmpty") + "\n"
		else {
			long totalQueuedDownloadSize = 0;
			long totalQueuedDownload = 0;
			long totalQueuedUploadSize = 0;
			long totalQueuedUpload = 0;
			for(int i=0;i<reqs.length;i++) {
				RequestStatus req = reqs[i];
				if(req instanceof DownloadRequestStatus && !uploads) {
					totalQueuedDownload++;
					DownloadRequestStatus download = (DownloadRequestStatus)req;
					long size = download.getDataSize();
					if(size > 0)
						totalQueuedDownloadSize += size;
				} else if(req instanceof UploadFileRequestStatus && uploads) {
					totalQueuedUpload++;
					UploadFileRequestStatus upload = (UploadFileRequestStatus)req;
					long size = upload.getDataSize();
					if(size > 0)
						totalQueuedUploadSize += size;
				} else if(req instanceof UploadDirRequestStatus && uploads) {
					totalQueuedUpload++;
					UploadDirRequestStatus upload = (UploadDirRequestStatus)req;
					long size = upload.getTotalDataSize();
					if(size > 0)
						totalQueuedUploadSize += size;
				}
			}
			text += "Downloads Queued: " + totalQueuedDownload + " (" + totalQueuedDownload + ")\n";
			text += "Uploads Queued: " + totalQueuedUpload + " (" + totalQueuedUpload + ")\n";
		}

		this.writeTextReply(ctx, 200, "OK", text);
	}

	private static String l10n(String key) {
		return NodeL10n.ENGLISH.getString("StatisticsToadlet."+key);
	}

	private static String l10nDark(String key) {
		return NodeL10n.ENGLISH.getString("DarknetConnectionsToadlet."+key);
	}

	private static String l10n(String key, String pattern, String value) {
		return NodeL10n.ENGLISH.getString("StatisticsToadlet."+key, new String[] { pattern }, new String[] { value });
	}

	private static String l10n(String key, String[] patterns, String[] values) {
		return NodeL10n.ENGLISH.getString("StatisticsToadlet."+key, patterns, values);
	}

	@Override
	public String path() {
		return "/diagnostic/";
	}
}