package freenet.clients.http;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
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
import java.util.Iterator;
import java.util.Locale;
import java.util.ArrayList;

import freenet.client.DefaultMIMETypes;
import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.HTTPRequestImpl.HTTPUploadedFileImpl;
import freenet.io.comm.AsyncMessageCallback;
import freenet.io.comm.ByteCounter;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;
import freenet.io.xfer.BlockTransmitter;
import freenet.io.xfer.BulkTransmitter;
import freenet.io.xfer.PartiallyReceivedBulk;
import freenet.l10n.NodeL10n;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.OpennetManager;
import freenet.node.PeerManager;
import freenet.node.LinkStatistics;
import freenet.node.PeerNode;
import freenet.support.BandwidthStatsContainer;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.SizeUtil;
import freenet.support.api.Bucket;
import freenet.support.api.HTTPRequest;
import freenet.support.io.ArrayBucket;
import freenet.support.io.ByteArrayRandomAccessThing;
import freenet.support.io.RandomAccessThing;

import freenet.node.DarknetPeerNode;
import freenet.support.MultiValueTable;

// JFreeChart

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.ChartUtilities;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.renderer.category.LineAndShapeRenderer;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.title.TextTitle;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.jfree.chart.plot.XYPlot;

/* FIXME: Localize all this and check for grammar! */

public class LinkStatisticsToadlet extends Toadlet {

	public static int SEND_TIMEOUT = BlockTransmitter.SEND_TIMEOUT;
	
	private long transitionStarted = -1;
	private long transitionFinished = -1;
	private long transitionTime = -1;
	
	private LinkStatistics trackedStats = null;
	private DarknetPeerNode testedNode = null;
	
	static class SampleData {
		public ArrayList<Long> times = new ArrayList<Long>();
		public ArrayList<Double> values = new ArrayList<Double>();
		public synchronized void addPair(long a, double b) {
			times.add(a);
			values.add(b);
		}
	}
	
	SampleData cwndSamples = null;
	SampleData dataInFlightSamples = null;
	
	public final static ByteCounter emptyByteCounter = new ByteCounter() {
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
	/* Will need this for plotting and bandwith measuring purposes */
	final LinkStatistics.StatsChangeTracker statsTracker = new LinkStatistics.StatsChangeTracker() {
		@Override
		public void dataSentChanged(long previousval, long newval, long time) {
		}
		public void dataLostChanged(long previousval, long newval, long time) {
		}
		@Override
		public void messagePayloadSentChanged(long previousval, long newval, long time) {
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
			synchronized (this) {
				if (LinkStatisticsToadlet.this.transitionInProcess()) {
					cwndSamples.addPair(time - transitionStarted, newval);
					// That's the reason to call all the tracker's callbacks inside sync block in LinkStats
					dataInFlightSamples.addPair(time - transitionStarted, trackedStats.getDataInFlight() / Node.PACKET_SIZE);
				}
			}
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
	public void handleMethodGET(URI uri, HTTPRequest request, ToadletContext ctx) 
			throws ToadletContextClosedException, IOException, RedirectException {
		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, 
					NodeL10n.getBase().getString("Toadlet.unauthorizedTitle"), NodeL10n.getBase().getString("Toadlet.unauthorized"));
			return;
		}
		
		if (request.isParameterSet("peernode_hashcode")) {
			
			transitionStarted = -1;
			transitionFinished = -1;
			transitionTime = -1;
			
			trackedStats = null;
			testedNode = null;
			cwndSamples = new SampleData();
			dataInFlightSamples = new SampleData();
			
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
		} else if (request.isParameterSet("generate_plot")) {
			Bucket data = ctx.getBucketFactory().makeBucket(-1);
			OutputStream os = data.getOutputStream();
			try {
				XYSeriesCollection cwndToTimeDataset = new XYSeriesCollection();
				cwndToTimeDataset = addSeriesToDataset(cwndToTimeDataset, "CWND from Time", cwndSamples.times, cwndSamples.values);
				cwndToTimeDataset = addSeriesToDataset(cwndToTimeDataset, "DataInFlight from Time", 
						dataInFlightSamples.times, dataInFlightSamples.values);
				JFreeChart chart = createChart("Data amounts from time", "Time", "Full packets", cwndToTimeDataset);
				XYPlot plot = (XYPlot) chart.getPlot();
				//XYLineAndShapeRenderer renderer = (XYLineAndShapeRenderer) plot.getRenderer();
				//renderer.setBaseShapesVisible(true);
				//renderer.setBaseShapesFilled(false);
				chart.setBackgroundPaint(new Color(232, 232, 232));
				plot.setBackgroundPaint(new Color(240, 240, 240));
				ChartUtilities.writeChartAsPNG(os, chart, 500, 300);
			} finally {
				os.close();
			}
			MultiValueTable<String, String> headers = new MultiValueTable<String, String>();
			ctx.sendReplyHeaders(200, "OK", headers, "image/png", data.size(), null);
			ctx.writeData(data);
		} else {
			MultiValueTable<String, String> headers = new MultiValueTable<String, String>();
			headers.put("Location", "/friends/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
		}
	}

	public void handleMethodPOST(URI uri, HTTPRequest request, ToadletContext ctx) 
			throws ToadletContextClosedException, IOException, RedirectException {
		
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
			long sessionUID = node.random.nextLong();
			
			try { // Initiate a session and transfer a bulk of a given size
				transitionFinished = -1;
				// Initiate
				testedNode.sendAsync(DMT.createFNPLinkTestInitiator(sessionUID, sampleSize), null, emptyByteCounter);
				// Wait for reply
				if (waitForAccepted(sessionUID, SEND_TIMEOUT)) {
					// Got confirmation - start sampling
					ByteArrayRandomAccessThing rat = new ByteArrayRandomAccessThing(new byte [sampleSize]);
					PartiallyReceivedBulk prb = new PartiallyReceivedBulk(node.getUSM(), sampleSize, Node.PACKET_SIZE, rat, true);
					BulkTransmitter sender = new BulkTransmitter(prb, testedNode, sessionUID, false, emptyByteCounter, false);
					transitionStarted = System.currentTimeMillis();
					if (sender.send()) {
						transitionFinished = System.currentTimeMillis();
					}
				} else {
					super.sendErrorPage(ctx, 403, 
							"FATAL: Node refused the probe", "Sampling failed: node ("+testedNode.getName()+") refused to start a test");
				}
			} catch (NotConnectedException e) {
				// Ignore here, handle below
			} catch (DisconnectedException e) {
				// Ignore here, handle below
			}

			if (transitionFinished == -1) {
				trackedStats.attachListener(null);
				super.sendErrorPage(ctx, 403, "FATAL: No connection to node", "Sampling failed: no connection to node.");
				return;
			} else {
				transitionTime = transitionFinished - transitionStarted;
			}
			
			contentNode.addChild(ctx.getAlertManager().createSummary());
			HTMLNode plotNode = contentNode.addChild("img", "src", path() + "?generate_plot");
			plotNode.addAttribute("border", "1");
			plotNode.addAttribute("width", "500");
			plotNode.addAttribute("height", "300");
			
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
			dataStatsList.addChild("li", "Usefull data (does not include overhead) sent:" + '\u00a0' + trackedStats.getMessagePayloadSent());
			
			dataStatsInfobox = commonStatsContent.addChild("div", "class", "infobox");
			dataStatsInfobox.addChild("div", "class", "infobox-header", "Troubles encountered");
			dataStatsContent = dataStatsInfobox.addChild("div", "class", "infobox-content");
			dataStatsList = dataStatsContent.addChild("ul");
			dataStatsList.addChild("li", "Bytes lost:" + '\u00a0' + trackedStats.getDataLost());
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
			
			/* Graphs */
			
			
			
			contentNode.addChild("br");
			//HTMLNode nextPlotCell = contentNode.addChild("br");
			
	
			trackedStats.attachListener(null);

			this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
			return;
		}
		
		MultiValueTable<String, String> headers = new MultiValueTable<String, String>();
		headers.put("Location", "/friends/");
		ctx.sendReplyHeaders(302, "Found", headers, null, 0);
	}
	
	private XYSeriesCollection addSeriesToDataset(XYSeriesCollection dataset, String name, ArrayList<Long> xs, ArrayList<Double> ys) {
		XYSeries series = new XYSeries(name);
		Iterator <Long> xIt = xs.iterator();
		Iterator <Double> yIt = ys.iterator();
		while (xIt.hasNext() && yIt.hasNext())
			series.add(xIt.next(), yIt.next());
		dataset.addSeries(series);
		return dataset;
	}
	
	private JFreeChart createChart(String name, String domainName, String valueName, XYSeriesCollection dataset) {
		JFreeChart chart = ChartFactory.createXYLineChart(
				name,
				domainName,
				valueName,
				dataset,
				PlotOrientation.VERTICAL,
				true,
				false,
				false);
		return chart;
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
	
	/**
	 * @param sessionUID - uid for sampling session
	 * @param timeout - max time that we will wait until a message is received
	 * @return true if received FNPAccepted, false if received FNPRejectedOverload
	 * @throws DisconnectedException - in case of wait timeout or absence of connection to {@code testedNode}
	 */
	private boolean waitForAccepted(long sessionUID, int timeout) throws DisconnectedException {
		MessageFilter mf = createAcceptedRejectedOverloadFilter(sessionUID, timeout);
		Message msg = node.getUSM().waitFor(mf, null);
		
		if(msg == null) {
			// Timeout waiting for message
			throw new DisconnectedException();
		}
		
		if(msg.getSpec() == DMT.FNPRejectedOverload) {
			return false;
		} else if(msg.getSpec() == DMT.FNPAccepted) {
			return true;
		} else {
			// Received unknown message type - shouldn't happen
			return false;
		}
		
	}
	
	private MessageFilter createAcceptedRejectedOverloadFilter(long uid, int timeout) {
		MessageFilter mfAccepted = MessageFilter.create().setSource(testedNode) 
				.setField(DMT.UID, uid).setTimeout(timeout).setType(DMT.FNPAccepted);
		MessageFilter mfRejectedOverload = MessageFilter.create().setSource(testedNode) 
				.setField(DMT.UID, uid).setTimeout(timeout).setType(DMT.FNPRejectedOverload);
		return mfAccepted.or(mfRejectedOverload);
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
	
	protected boolean transitionInProcess() {
		return ((transitionStarted > 0) && (transitionFinished == -1));
	}

	@Override
	public String path() {
		return "/test_connection/";
	}
}
