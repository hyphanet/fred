package freenet.clients.http;

import java.io.IOException;
import java.net.URI;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.Format;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

import freenet.client.DefaultMIMETypes;
import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.HTTPRequestImpl.HTTPUploadedFileImpl;
import freenet.io.comm.AsyncMessageCallback;
import freenet.io.comm.ByteCounter;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.NotConnectedException;
import freenet.l10n.NodeL10n;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.OpennetManager;
import freenet.node.PeerManager;
import freenet.node.LinkStatistics;
import freenet.support.BandwidthStatsContainer;
import freenet.support.HTMLNode;
import freenet.support.SimpleFieldSet;
import freenet.support.SizeUtil;
import freenet.support.api.HTTPRequest;
import freenet.support.io.ArrayBucket;
import freenet.support.io.ByteArrayRandomAccessThing;
import freenet.support.io.RandomAccessThing;

import freenet.node.DarknetPeerNode;
import freenet.support.MultiValueTable;


/* FIXME: Localize all this and check for grammar! */

public class LinkStatisticsToadlet extends Toadlet {

	private long transitionStarted = -1;
	private long transitionFinished = -1;
	private long transitionTime = -1;
	private boolean transitionComplete = false;
	private LinkStatistics trackedStats = null;
	DarknetPeerNode testedNode = null;
	
	final ByteCounter emptyByteCounter = new ByteCounter() {
		// Just ignore all that, is already gathered by a stats tracker
		@Override
		public void receivedBytes(int x) {
		}
		@Override
		public void sentBytes(int x) {
		}
		@Override
		public void sentPayload(int x) {
		}

	};
	final AsyncMessageCallback transitionCallback = new AsyncMessageCallback() {
		synchronized public void sent() {
			trackedStats.reset();
			transitionStarted = System.currentTimeMillis();
		}
	    synchronized public void acknowledged() {
				transitionFinished = System.currentTimeMillis();
				transitionComplete = true;
				notifyAll();
	    }
	    synchronized public void disconnected() {
				transitionComplete = true;
				notifyAll();
	    }
	    synchronized public void fatalError() {
				transitionComplete = true;
				notifyAll();
	    }
	};
	/* Will need this for plotting and bandwith measuring purposes */
	final LinkStatistics.StatsChangeTracker statsTracker = new LinkStatistics.StatsChangeTracker() {
		@Override
		public void dataSentChanged(long previousval, long newval, long time) {
		}
		@Override
		public void usefullPaybackSentChanged(long previousval, long newval, long time) {
	    }
		@Override
		public void acksSentChanged(long previousval, long newval, long time) {
	    }
		@Override
		public void pureAcksSentChanged(long previousval, long newval, long time) {
	    }
		@Override
		public void dataRetransmittedChanged(long previousval, long newval, long time) {
	    }
		@Override
		public void seriousBackoffsChanged(long previousval, long newval, long time) {
	    }
		@Override
		public void queueBacklogChanged(double previousval, double newval, long time) {
	    }
		@Override
		public void windowSizeChanged(double previousval, double newval, long time) {
	    }
		@Override
		public void maxUsedWindowChanged(double previousval, double newval, long time) {
	    }
		@Override
		public void averageRTTChanged(double previousval, double newval, long time) {
	    }
		@Override
		public void RTOChanged(double previousval, double newval, long time) {
	    }
	};

	private final Node node;

	protected LinkStatisticsToadlet(Node n, HighLevelSimpleClient client) {
		super(client);
		this.node = n;
	}
	
	@Override
	public void handleMethodGET(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, NodeL10n.getBase().getString("Toadlet.unauthorizedTitle"), NodeL10n.getBase().getString("Toadlet.unauthorized"));
			return;
		}
		
		if (request.isParameterSet("peernode_hashcode")) {
			PageNode page = ctx.getPageMaker().getPageNode(l10n("sendMessage"), ctx);
			HTMLNode pageNode = page.outer;
			HTMLNode contentNode = page.content;
	
			String input_hashcode_string = request.getParam("peernode_hashcode");
			DarknetPeerNode pn = findPeerByRequest(request);
			if (pn == null) {
				contentNode.addChild(createPeerInfobox("infobox-error",
						l10n("peerNotFoundTitle"), l10n("peerNotFoundWithHash",
								"hash", input_hashcode_string)));
			} else {
				createSampleSizeSendForm(pageNode, contentNode, ctx, pn.getName());
				testedNode = pn;
			}
			this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
		}
		
		MultiValueTable<String, String> headers = new MultiValueTable<String, String>();
		headers.put("Location", "/friends/");
		ctx.sendReplyHeaders(302, "Found", headers, null, 0);
	}

	public void handleMethodPOST(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		
		if (request.isPartSet("size")) {
			PageNode page = ctx.getPageMaker().getPageNode(l10n("sendMessage"), ctx);
			HTMLNode pageNode = page.outer;
			HTMLNode contentNode = page.content;

			if (testedNode == null) {
				contentNode.addChild(createPeerInfobox("infobox-error",
						l10n("peerNotFoundTitle"), "Failed to find a peer with given hashcode"));
				this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
				return;
			}
			
			String value = request.getPartAsStringFailsafe("sample_size", 1024);
			int sampleSize;
			try {
				sampleSize = Integer.parseInt(value);
			} catch (NumberFormatException e) {
				sampleSize = -1;
			}
			if (sampleSize <= 0) {
				contentNode.addChild(createPeerInfobox("infobox-error",
						"Illegal sample data size", "sample_size should be greater than zero"));
				this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
				return;
			}
			
			trackedStats = testedNode.getTotalLinkStats();
			trackedStats.attachListener(statsTracker);
			
			HTTPUploadedFileImpl data = 
					new HTTPUploadedFileImpl("sample", DefaultMIMETypes.DEFAULT_MIME_TYPE, new ArrayBucket(new byte [sampleSize]));
			testedNode.sendFileOffer(data, "sample", transitionCallback);
			
			try {
				synchronized (transitionCallback) {
					while (!transitionComplete) {
						transitionCallback.wait();
					}
				}
			} catch (InterruptedException e) {
				// FIXME: Do something?
			}

			if (transitionFinished == -1) {
				super.sendErrorPage(ctx, 403, "FATAL: No connection to node", "Sampling failed: no connection to node");
				return;
			} else {
				transitionTime = transitionFinished - transitionStarted;
			}
			
			contentNode.addChild(ctx.getAlertManager().createSummary());
			HTMLNode overviewTable = contentNode.addChild("table", "class", "column");
			HTMLNode overviewTableRow = overviewTable.addChild("tr");
			HTMLNode nextTableCell = overviewTableRow.addChild("td", "class", "first");

			/* Common stats */
			HTMLNode commonStatsBox = nextTableCell.addChild("div", "class", "infobox");
			commonStatsBox.addChild("div", "class", "infobox-header", "Common stats");
			HTMLNode commonStatsContent = commonStatsBox.addChild("div", "class", "infobox-content");
			
			HTMLNode dataStatsInfobox = commonStatsContent.addChild("div", "class", "infobox");
			dataStatsInfobox.addChild("div", "class", "infobox-header", "Data-related stats:");
			HTMLNode dataStatsContent = dataStatsInfobox.addChild("div", "class", "infobox-content");
			HTMLNode dataStatsList = dataStatsContent.addChild("ul");
			dataStatsList.addChild("li", "Data sent:" + '\u00a0' + trackedStats.getDataSent());
			dataStatsList.addChild("li", "Data acknowledged:" + '\u00a0' + trackedStats.getDataAcked());
			dataStatsList.addChild("li", "Usefull data (does not include overhead) sent:" + '\u00a0' + trackedStats.getUsefullPaybackSent());
			
			dataStatsInfobox = commonStatsContent.addChild("div", "class", "infobox");
			dataStatsInfobox.addChild("div", "class", "infobox-header", "Troubles encountered");
			dataStatsContent = dataStatsInfobox.addChild("div", "class", "infobox-content");
			dataStatsList = dataStatsContent.addChild("ul");
			dataStatsList.addChild("li", "Retransmit count:" + '\u00a0' + trackedStats.getRetransmitCount());
			dataStatsList.addChild("li", "Amount of data retransmitted:" + '\u00a0' + trackedStats.getDataRetransmitted());
			dataStatsList.addChild("li", "Serious backoffs encountered:" + '\u00a0' + trackedStats.getSeriousBackoffs());

			
			/* Congestion Control */
			dataStatsInfobox = commonStatsContent.addChild("div", "class", "infobox");
			dataStatsInfobox.addChild("div", "class", "infobox-header", "Congestion control");
			dataStatsContent = dataStatsInfobox.addChild("div", "class", "infobox-content");
			dataStatsList = dataStatsContent.addChild("ul");
			dataStatsList.addChild("li", "Current congestion window size:" + '\u00a0' + trackedStats.getWindowSize());
			dataStatsList.addChild("li", "Maximum congestion winodw utilized (currently not tracked):" + '\u00a0' + trackedStats.getMaxUsedWindow());
			dataStatsList.addChild("li", "Current RTO:" + '\u00a0' + trackedStats.getRTO());
			dataStatsList.addChild("li", "Average RTT:" + '\u00a0' + trackedStats.getAverageRTT());
			

			nextTableCell = overviewTableRow.addChild("td", "class", "first");
			
			
			/* Times */
			commonStatsBox = nextTableCell.addChild("div", "class", "infobox");
			commonStatsBox.addChild("div", "class", "infobox-header", "Times");
			commonStatsContent = commonStatsBox.addChild("div", "class", "infobox-content");
			
			dataStatsInfobox = commonStatsContent.addChild("div", "class", "infobox");
			dataStatsInfobox.addChild("div", "class", "infobox-header", "Transition started:" + '\u00a0' 
																	+ convertTime(transitionStarted, "HH:mm:ss.SS"));
			dataStatsInfobox = commonStatsContent.addChild("div", "class", "infobox");
			dataStatsInfobox.addChild("div", "class", "infobox-header", "Transition finished:" + '\u00a0' 
																	+ convertTime(transitionFinished, "HH:mm:ss.SS"));
			dataStatsInfobox = commonStatsContent.addChild("div", "class", "infobox");
			dataStatsInfobox.addChild("div", "class", "infobox-header", "Transition Time:" + '\u00a0' + transitionTime);
			
			
			/* Veno */
			commonStatsBox = nextTableCell.addChild("div", "class", "infobox");
			commonStatsBox.addChild("div", "class", "infobox-header", "Veno-related stats");
			commonStatsContent = commonStatsBox.addChild("div", "class", "infobox-content");
			dataStatsInfobox = commonStatsContent.addChild("div", "class", "infobox");
			dataStatsInfobox.addChild("div", "class", "infobox-header", "Inner message queue (currently not tracked)");
			dataStatsContent = dataStatsInfobox.addChild("div", "class", "infobox-content");
			dataStatsList = dataStatsContent.addChild("ul");
			dataStatsList.addChild("li", "Current queue backlog size:" + '\u00a0' + trackedStats.getQueueBacklog());

			this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
			return;
		}
		
		MultiValueTable<String, String> headers = new MultiValueTable<String, String>();
		headers.put("Location", "/friends/");
		ctx.sendReplyHeaders(302, "Found", headers, null, 0);
	}
	
	private DarknetPeerNode findPeerByRequest(HTTPRequest request) {
		DarknetPeerNode foundNode = null;
		if (request.isParameterSet("peernode_hashcode")) {
			String input_hashcode_string = request.getParam("peernode_hashcode");
			int input_hashcode = -1;
			try {
				input_hashcode = (Integer.valueOf(input_hashcode_string)).intValue();
			} catch (NumberFormatException e) {
				// Ignore, will result in returning null
			}
			if (input_hashcode != -1) {
				DarknetPeerNode[] peerNodes = node.getDarknetConnections();
				for (DarknetPeerNode pn: peerNodes) {
					int peer_hashcode = pn.hashCode();
					if (peer_hashcode == input_hashcode) {
						foundNode = pn;
						break;
					}
				}
			}
		}
		return foundNode;
	}

	private static String l10n(String key) {
		return NodeL10n.getBase().getString("StatisticsToadlet."+key);
	}

	private static String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("StatisticsToadlet."+key, new String[] { pattern }, new String[] { value });
	}

	private static HTMLNode createPeerInfobox(String infoboxType,
			String header, String message) {
		HTMLNode infobox = new HTMLNode("div", "class", "infobox "+infoboxType);
		infobox.addChild("div", "class", "infobox-header", header);
		HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
		infoboxContent.addChild("#", message);
		HTMLNode list = infoboxContent.addChild("ul");
		Toadlet.addHomepageLink(list);
		list.addChild("li").addChild("a", new String[] { "href", "title" },
		        new String[] { "/friends/", l10n("returnToFriends") },
		        l10n("friends"));
		return infobox;
	}
	
	public static void createSampleSizeSendForm(HTMLNode pageNode, HTMLNode contentNode, ToadletContext ctx, String PeerHandle)
			throws ToadletContextClosedException, IOException {
		HTMLNode infobox = contentNode.addChild("div", new String[] { "class",
				"id" }, new String[] { "infobox", "n2nbox" });
		infobox.addChild("div", "class", "infobox-header", "Data size");
		HTMLNode testingTarget = infobox.addChild("div", "class",
				"infobox-content");
		testingTarget.addChild("p", "About to test the node (" + PeerHandle + ") - enter the sampling data size (in bytes)");
		
		HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
		HTMLNode messageForm = ctx.addFormChild(infoboxContent, "/test_connection/", "sendN2NTMForm");
		messageForm.addChild("textarea", new String[] { "id", "name", "rows",
				"cols" }, new String[] { "sample_size", "sample_size", "1", "5" });
		messageForm.addChild("br");
		messageForm.addChild("br");

		messageForm.addChild("input", new String[] { "type", "name", "value" },
				new String[] { "submit", "size", "Test connection" });
	}
	
	public String convertTime(long time, String format){
	    Date date = new Date(time);
	    Format formatWith = new SimpleDateFormat(format);
	    return formatWith.format(date).toString();
	}

	@Override
	public String path() {
		return "/test_connection/";
	}
}
