package freenet.clients.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;

import freenet.client.HighLevelSimpleClient;
import freenet.io.comm.PeerParseException;
import freenet.node.FSParseException;
import freenet.node.Node;
import freenet.node.PeerNode;
import freenet.support.Bucket;
import freenet.support.HTMLEncoder;
import freenet.support.MultiValueTable;
import freenet.support.SimpleFieldSet;

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

	private Node node;
	
	protected DarknetConnectionsToadlet(Node n, HighLevelSimpleClient client) {
		super(client);
		this.node = n;
	}

	public String supportedMethods() {
		return "GET, POST";
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
		StringBuffer buf = new StringBuffer(1024);
		
		//HTTPRequest request = new HTTPRequest(uri);
		
		final boolean advancedEnabled = node.getToadletContainer().isAdvancedDarknetEnabled();
		
		/* gather connection statistics */
		int numberOfConnected = node.getPeerNodeStatusSize(Node.PEER_NODE_STATUS_CONNECTED);
		int numberOfRoutingBackedOff = node.getPeerNodeStatusSize(Node.PEER_NODE_STATUS_ROUTING_BACKED_OFF);
		int numberOfTooNew = node.getPeerNodeStatusSize(Node.PEER_NODE_STATUS_TOO_NEW);
		int numberOfTooOld = node.getPeerNodeStatusSize(Node.PEER_NODE_STATUS_TOO_OLD);
		int numberOfDisconnected = node.getPeerNodeStatusSize(Node.PEER_NODE_STATUS_DISCONNECTED);
		int numberOfNeverConnected = node.getPeerNodeStatusSize(Node.PEER_NODE_STATUS_NEVER_CONNECTED);
		int numberOfDisabled = node.getPeerNodeStatusSize(Node.PEER_NODE_STATUS_DISABLED);
		
		int numberOfSimpleConnected = numberOfConnected + numberOfRoutingBackedOff;
		int numberOfNotConnected = numberOfTooNew + numberOfTooOld + numberOfDisconnected + numberOfNeverConnected + numberOfDisabled;
		String titleCountString = null;
		if(advancedEnabled) {
			titleCountString = "(" + numberOfConnected + "/" + numberOfRoutingBackedOff + "/" + numberOfNotConnected + ")";
		} else {
			titleCountString = new Integer(numberOfSimpleConnected).toString();
		}
		
		String pageTitle = titleCountString + " Darknet Peers";
		
		ctx.getPageMaker().makeHead(buf, pageTitle);
		
		// FIXME! We need some nice images
		PeerNode[] peerNodes = node.getDarknetConnections();
		
		long now = System.currentTimeMillis();
		
		node.alerts.toSummaryHtml(buf);
		
		/* node status values */
		int bwlimitDelayTime = (int) node.getBwlimitDelayTime();
		int nodeAveragePingTime = (int) node.getNodeAveragePingTime();
		int networkSizeEstimate = (int) node.getNetworkSizeEstimate( 0 );
		DecimalFormat fix4 = new DecimalFormat("0.0000");
		double missRoutingDistance =  node.MissRoutingDistance.currentValue();
		DecimalFormat fix1 = new DecimalFormat("##0.0%");
		double backedoffPercent =  node.BackedoffPercent.currentValue();
		String nodeUptimeString = timeIntervalToString(( now - node.startupTime ) / 1000);
		
		buf.append("<table class=\"column\"><tr><td class=\"first\">");
		
		/* node status overview box */
		if(advancedEnabled) {
			buf.append("<div class=\"infobox\">");
			buf.append("<div class=\"infobox-header\">Node status overview</div>");
			buf.append("<div class=\"infobox-content\">");
			buf.append("<ul>");
			buf.append("<li>bwlimitDelayTime:&nbsp;").append(bwlimitDelayTime).append("ms</li>");
			buf.append("<li>nodeAveragePingTime:&nbsp;").append(nodeAveragePingTime).append("ms</li>");
			buf.append("<li>networkSizeEstimate:&nbsp;").append(networkSizeEstimate).append("&nbsp;nodes</li>");
			buf.append("<li>nodeUptime:&nbsp;").append(nodeUptimeString).append("</li>");
			buf.append("<li>missRoutingDistance:&nbsp;").append(fix4.format(missRoutingDistance)).append("</li>");
			buf.append("<li>backedoffPercent:&nbsp;").append(fix1.format(backedoffPercent)).append("</li>");
			buf.append("</ul></div>");
			buf.append("</div>\n");
			buf.append("</td><td>");
		}
		
		// Activity box
		int numInserts = node.getNumInserts();
		int numRequests = node.getNumRequests();
		int numTransferringRequests = node.getNumTransferringRequests();
		int numARKFetchers = node.getNumARKFetchers();
		
		buf.append("<div class=\"infobox\">\n");
		buf.append("<div class=\"infobox-header\">\n");
		buf.append("Current Activity\n");
		buf.append("</div>\n");
		buf.append("<div class=\"infobox-content\">\n");
		if ((numInserts == 0) && (numRequests == 0) && (numTransferringRequests == 0) && (numARKFetchers == 0)) {
			buf.append("Your node is not processing any requests right now.");
		} else {
			buf.append("<ul id=\"activity\">\n");
			if (numInserts > 0) {
				buf.append("<li>Inserts:&nbsp;").append(numInserts).append("</li>");
			}
			if (numRequests > 0) {
				buf.append("<li>Requests:&nbsp;").append(numRequests).append("</li>");
			}
			if (numTransferringRequests > 0) {
				buf.append("<li>Transferring&nbsp;Requests:&nbsp;").append(numTransferringRequests).append("</li>");
			}
			if(advancedEnabled) {
				if (numARKFetchers > 0) {
					buf.append("<li>ARK&nbsp;Fetch&nbsp;Requests:&nbsp;").append(numARKFetchers).append("</li>");
				}
			}
			buf.append("</ul>\n");
		}
		buf.append("</div>\n");
		buf.append("</div>\n");
		
		buf.append("</td><td>");
		
		// Peer statistics box
		buf.append("<div class=\"infobox\">");
		buf.append("<div class=\"infobox-header\">Peer statistics</div>");
		buf.append("<div class=\"infobox-content\">");
		buf.append("<ul>");
		if (numberOfConnected > 0) {
			buf.append("<li><span class=\"peer_connected\">Connected:&nbsp;").append(numberOfConnected).append("</span></li>");
		}
		if (numberOfRoutingBackedOff > 0) {
			String backoffName = "Busy";
			if(advancedEnabled) {
				backoffName = "Backed off";
			}
			buf.append("<li><span class=\"peer_backedoff\">").append(backoffName).append(":&nbsp;").append(numberOfRoutingBackedOff).append("</span></li>");
		}
		if (numberOfTooNew > 0) {
			buf.append("<li><span class=\"peer_too_new\">Too new:&nbsp;").append(numberOfTooNew).append("</span></li>");
		}
		if (numberOfTooOld > 0) {
			buf.append("<li><span class=\"peer_too_old\">Too old:&nbsp;").append(numberOfTooOld).append("</span></li>");
		}
		if (numberOfDisconnected > 0) {
			buf.append("<li><span class=\"peer_disconnected\">Disconnected:&nbsp;").append(numberOfDisconnected).append("</span></li>");
		}
		if (numberOfNeverConnected > 0) {
			buf.append("<li><span class=\"peer_never_connected\">Never Connected:&nbsp;").append(numberOfNeverConnected).append("</span></li>");
		}
		if (numberOfDisabled > 0) {
			buf.append("<li><span class=\"peer_never_connected\">Disabled:&nbsp;").append(numberOfDisabled).append("</span></li>");  // **FIXME**
		}
		buf.append("</ul>");
		buf.append("</div>");
		buf.append("</div>\n");
		
		// Peer routing backoff reason box
		if(advancedEnabled) {
			buf.append("</td><td class=\"last\">");
			buf.append("<div class=\"infobox\">");
			buf.append("<div class=\"infobox-header\">Peer backoff reasons</div>");
			buf.append("<div class=\"infobox-content\">");
			String [] routingBackoffReasons = node.getPeerNodeRoutingBackoffReasons();
			if(routingBackoffReasons.length == 0) {
				buf.append("Good, your node is not backed off from any peers!<br/>\n");
			} else {
				buf.append("<ul>\n");
				for(int i=0;i<routingBackoffReasons.length;i++) {
					int reasonCount = node.getPeerNodeRoutingBackoffReasonSize(routingBackoffReasons[i]);
					if(reasonCount > 0) {
						buf.append("<li>").append(routingBackoffReasons[i]).append(":&nbsp;").append(reasonCount).append("</li>\n");
					}
				}
				buf.append("</ul>\n");
			}
			buf.append("</div>");
			buf.append("</div>\n");
		}
		
		buf.append("</td></tr></table>\n");
		
		buf.append("<div class=\"infobox infobox-normal\">\n");
		buf.append("<div class=\"infobox-header\">\n");
		buf.append("My Peers");
		if(advancedEnabled) {
			if (!path.endsWith("displaymessagetypes.html"))
			{
				buf.append(" <a href=\"displaymessagetypes.html\">(more detailed)</a>");
			}
		}
		buf.append("</div>\n");
		buf.append("<div class=\"infobox-content\">\n");
		buf.append("<form action=\".\" method=\"post\" enctype=\"multipart/form-data\">\n");
		StringBuffer buf2 = new StringBuffer(1024);
		buf2.append("<table class=\"darknet_connections\">\n");
		buf2.append(" <tr>\n");
		buf2.append("  <th></th>\n");
		buf2.append("  <th>Status</th>\n");
		buf2.append("  <th><span title=\"Click on the nodename link to send a N2NTM\" style=\"border-bottom:1px dotted;cursor:help;\">Name</span></th>\n");
		if(advancedEnabled) {
			buf2.append("  <th><span title=\"Address:Port\" style=\"border-bottom:1px dotted;cursor:help;\">Address</span></th>\n");
		}
		buf2.append("  <th>Version</th>\n");
		if(advancedEnabled) {
			buf2.append("  <th>Location</th>\n");
			buf2.append("  <th><span title=\"Temporarily disconnected. Other node busy? Wait time(s) remaining/total\" style=\"border-bottom:1px dotted;cursor:help;\">Backoff</span></th>\n");
		}
		buf2.append("  <th><span title=\"How long since the node was last seen\" style=\"border-bottom:1px dotted;cursor:help;\">Idle</span></th>\n");
		buf2.append(" </tr>\n");
		
		if (peerNodes.length == 0) {
			buf2.append("<tr><td colspan=\"8\">Freenet can't work - you have not added any peers so far. <a href=\"/\">Go here</a> and read the top infobox to see how it's done.</td></tr>\n");
			buf2.append("</table>\n");
			//
			buf.append(buf2);
		}
		else {
			
			// Create array
			Object[][] rows = new Object[peerNodes.length][];
			for(int i=0;i<peerNodes.length;i++) {
				PeerNode pn = peerNodes[i];
				long routingBackedOffUntil = pn.getRoutingBackedOffUntil();
				int backoff = (int)(Math.max(routingBackedOffUntil - now, 0));
				// Don't list the backoff as zero before it's actually zero
				if(backoff > 0 && backoff < 1000 )
					backoff = 1000;
				
				// Elements must be HTML encoded.
				Object[] row = new Object[10];  // where [0] is the pn object and 9 is the node name only for sorting!
				rows[i] = row;
				
				Object status = new Integer(pn.getPeerNodeStatus());
				long idle = pn.lastReceivedPacketTime();
				if(((Integer) status).intValue() == Node.PEER_NODE_STATUS_NEVER_CONNECTED)
					idle = pn.getPeerAddedTime();
				String lastBackoffReasonOutputString = "";
				if(advancedEnabled) {
					String backoffReason = pn.getLastBackoffReason();
					if( backoffReason != null ) {
						lastBackoffReasonOutputString = "/"+backoffReason;
					} else {
						lastBackoffReasonOutputString = "/";
					}
				}
				String avgPingTimeString = "";
				if(advancedEnabled) {
					if(pn.isConnected()) {
						avgPingTimeString = " ("+(int) pn.averagePingTime()+"ms)";
					}
				}
				String versionPrefixString = "";
				String versionString = "";
				String versionSuffixString = "";
				if(pn.publicInvalidVersion() || pn.publicReverseInvalidVersion()) {
					versionPrefixString = "<span class=\"peer_version_problem\">";
					versionSuffixString = "</span>";
				}
				String namePrefixString = "";
				String nameSuffixString = "";
				if(pn.isConnected()) {
				  namePrefixString = "<a href=\"/send_n2ntm/?peernode_hashcode="+pn.hashCode()+"\">";
				  nameSuffixString = "</a>";
				}
				
				if(advancedEnabled) {
					versionString = HTMLEncoder.encode(pn.getVersion());
				} else {
					versionString = HTMLEncoder.encode(pn.getSimpleVersion());
				}

				row[0] = pn;
				row[1] = "<input type=\"checkbox\" name=\"node_"+pn.hashCode()+"\" />";
				row[2] = status;
				row[3] = namePrefixString+HTMLEncoder.encode(pn.getName())+nameSuffixString;
				row[4] = ( pn.getDetectedPeer() != null ? HTMLEncoder.encode(pn.getDetectedPeer().toString()) : "(address unknown)" ) + avgPingTimeString;
				row[5] = versionPrefixString+versionString+versionSuffixString;
				row[6] = new Double(pn.getLocation().getValue());
				row[7] = backoff/1000 + "/" + pn.getRoutingBackoffLength()/1000+lastBackoffReasonOutputString;
				row[8] = idleToString(now, idle, ((Integer) status).intValue());
				row[9] = HTMLEncoder.encode(pn.getName());
			}
	
			// Sort array
			Arrays.sort(rows, new MyComparator());
			
			// Convert status codes into status strings
			for(int i=0;i<rows.length;i++) {
				Object[] row = rows[i];
				String arkAsterisk = "";
				if(advancedEnabled) {
					if(((PeerNode) row[0]).isFetchingARK()) {
						arkAsterisk = "*";
					}
				}
				String statusString = ((PeerNode) row[0]).getPeerNodeStatusString();
				if(!advancedEnabled && ((PeerNode) row[0]).getPeerNodeStatus() == Node.PEER_NODE_STATUS_ROUTING_BACKED_OFF) {
					statusString = "BUSY";
				}
				row[2] = "<span class=\""+((PeerNode) row[0]).getPeerNodeStatusCSSClassName()+"\">"+statusString+arkAsterisk+"</span>";
			}
			
			// Turn array into HTML
			for(int i=0;i<rows.length;i++) {
				Object[] row = rows[i];
				buf2.append("<tr>");
				for(int j=1;j<row.length;j++) {  // skip index 0 as it's the PeerNode object
					if(j == 9) { // skip index 9 as it's used for sorting purposes only
				    	continue;
				    }
					if(!advancedEnabled) {  // if not in advanced Darknet page output mode
						if( j == 4 || j == 6 || j == 7 ) {  // skip IP address/name, location and backoff times
							continue;
						}
					}
					buf2.append("<td>"+row[j]+"</td>");
				}
				buf2.append("</tr>\n");
				
				if (path.endsWith("displaymessagetypes.html"))
				{
					buf2.append("<tr class=\"messagetypes\"><td colspan=\"8\">\n");
					buf2.append("<table class=\"sentmessagetypes\">\n");
					buf2.append("<tr><th>Sent Message Type</th><th>Count</th></tr>\n");
					for (Enumeration keys=((PeerNode)row[0]).getLocalNodeSentMessagesToStatistic().keys(); keys.hasMoreElements(); )
					{
						Object curkey = keys.nextElement();
						buf2.append("<tr><td>");
						buf2.append((String)curkey);
						buf2.append("</td><td>");
						buf2.append(((Long)((PeerNode)row[0]).getLocalNodeSentMessagesToStatistic().get(curkey)) + "");
						buf2.append("</td></tr>\n");
					}
					buf2.append("</table>\n");
		
					buf2.append("<table class=\"receivedmessagetypes\">\n");
					buf2.append("<tr><th>Received Message Type</th><th>Count</th></tr>\n");
					for (Enumeration keys=((PeerNode)row[0]).getLocalNodeReceivedMessagesFromStatistic().keys(); keys.hasMoreElements(); )
					{
						Object curkey = keys.nextElement();
						buf2.append("<tr><td>");
						buf2.append((String)curkey);
						buf2.append("</td><td>");
						buf2.append(((Long)((PeerNode)row[0]).getLocalNodeReceivedMessagesFromStatistic().get(curkey)) + "");
						buf2.append("</td></tr>\n");
					}
					buf2.append("</table>\n");
					buf2.append("</td></tr>\n");
				}
			}
			buf2.append("</table>\n");
			//
			buf.append(buf2);
			//
			if(!advancedEnabled) {
				buf.append("<input type=\"submit\" name=\"remove\" value=\"Remove selected peers\" />");
			} else {
				buf.append("<select name=\"action\">\n");
				buf.append(" <option value=\"\">-- Select Action --</option>\n");
				buf.append(" <option value=\"enable\">Enable Selected Peers</option>\n");
				buf.append(" <option value=\"disable\">Disable Selected Peers</option>\n");
				buf.append(" <option value=\"\">-- -- --</option>\n");
				buf.append(" <option value=\"remove\">Remove Selected Peers</option>\n");
				buf.append("</select>\n");
				buf.append("<input type=\"submit\" name=\"submit\" value=\"Go\" />\n");
				buf.append("&nbsp;&nbsp;&nbsp;<span class=\"darknet_connections\">* Requesting ARK</span>\n");
			}
			buf.append("<input type=\"hidden\" name=\"formPassword\" value=\"").append(node.formPassword).append("\" />");
			buf.append("</form>\n");
		}
		buf.append("</div>\n");
		buf.append("</div>\n");
		
		// new peer addition box
		buf.append("<div class=\"infobox infobox-normal\">\n");
		buf.append("<div class=\"infobox-header\">\n");
		buf.append("Add another peer\n");
		buf.append("</div>\n");
		buf.append("<div class=\"infobox-content\">\n");
		buf.append("<form action=\".\" method=\"post\" enctype=\"multipart/form-data\">\n");
		buf.append("Reference:<br />\n");
		buf.append("<textarea id=\"reftext\" name=\"ref\" rows=\"8\" cols=\"74\"></textarea>\n");
		buf.append("<br />\n");
		buf.append("or URL:\n");
		buf.append("<input id=\"refurl\" type=\"text\" name=\"url\" />\n");
		buf.append("<br />\n");
		buf.append("or file:\n");
		buf.append("<input id=\"reffile\" type=\"file\" name=\"reffile\" />\n");
		buf.append("<br />\n");
		buf.append("<input type=\"hidden\" name=\"formPassword\" value=\"").append(node.formPassword).append("\" />");
		buf.append("<input type=\"submit\" name=\"add\" value=\"Add\" />\n");
		buf.append("</form>\n");
		buf.append("</div>\n");
		buf.append("</div>\n");
		
		// our reference
		buf.append("<div class=\"infobox infobox-normal\">\n");
		buf.append("<div class=\"infobox-header\">\n");
		buf.append("<a href=\"myref.txt\">My Reference</a>\n");
		buf.append("</div>\n");
		buf.append("<div class=\"infobox-content\">\n");
		buf.append("<pre id=\"reference\">\n");
		buf.append(HTMLEncoder.encode(this.node.exportPublicFieldSet().toString()));
		buf.append("</pre>\n");
		buf.append("</div>\n");
		buf.append("</div>\n");
		
		ctx.getPageMaker().makeTail(buf);
		
		this.writeReply(ctx, 200, "text/html", "OK", buf.toString());
	}
	
	public void handlePost(URI uri, Bucket data, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		if(data.size() > 1024*1024) {
			this.writeReply(ctx, 400, "text/plain", "Too big", "Too much data, darknet toadlet limited to 1MB");
			return;
		}
		
		HTTPRequest request = new HTTPRequest(uri, data, ctx);
		
		String pass = request.getPartAsString("formPassword", 32);
		if(pass == null || !pass.equals(node.formPassword)) {
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/darknet/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
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
			
			String ref = "";
			if (urltext.length() > 0) {
				// fetch reference from a URL
				BufferedReader in = null;
				try {
					URL url = new URL(urltext);
					URLConnection uc = url.openConnection();
					in = new BufferedReader(new InputStreamReader(uc.getInputStream()));
					String line;
					while ( (line = in.readLine()) != null) {
						ref += line+"\n";
					}
				} catch (IOException e) {
					this.sendErrorPage(ctx, 200, "Failed To Add Node", "Unable to retrieve node reference from " + HTMLEncoder.encode(urltext) + ".<br /> <a href=\".\">Please try again</a>.");
					return;
				} finally {
					if( in != null ){
						in.close();
					}
				}
			} else if (reftext.length() > 0) {
				// read from post data or file upload
				// this slightly scary looking regexp chops any extra characters off the beginning or ends of lines and removes extra line breaks
				ref = reftext.replaceAll(".*?((?:[\\w,\\.]+\\=[^\r\n]+?)|(?:End))[ \\t]*(?:\\r?\\n)+", "$1\n");
			} else {
				this.sendErrorPage(ctx, 200, "Failed To Add Node", "Could not detect either a node reference or a URL.<br /> <a href=\".\">Please try again</a>.");
				request.freeParts();
				return;
			}
			ref = ref.trim();

			request.freeParts();
			// we have a node reference in ref
			SimpleFieldSet fs;
			
			try {
				fs = new SimpleFieldSet(ref, true);
			} catch (IOException e) {
				this.sendErrorPage(ctx, 200, "Failed To Add Node", "Unable to parse the given text: <pre>" + HTMLEncoder.encode(ref) + "</pre> as a node reference: "+HTMLEncoder.encode(e.toString())+".<br /> <a href=\".\">Please try again</a>.");
				return;
			}
			PeerNode pn;
			try {
				pn = new PeerNode(fs, this.node, false);
			} catch (FSParseException e1) {
				this.sendErrorPage(ctx, 200, "Failed To Add Node", "Unable to parse the given text: <pre>" + HTMLEncoder.encode(ref) + "</pre> as a node reference: " + HTMLEncoder.encode(e1.toString()) + ".<br /> Please <a href=\".\">Try again</a>");
				return;
			} catch (PeerParseException e1) {
				this.sendErrorPage(ctx, 200, "Failed To Add Node", "Unable to parse the given text: <pre>" + HTMLEncoder.encode(ref) + "</pre> as a node reference: " + HTMLEncoder.encode(e1.toString()) + ".<br /> Please <a href=\".\">Try again</a>");
				return;
			}
			if(pn.getIdentityHash()==node.getIdentityHash()) {
				this.sendErrorPage(ctx, 200, "Failed To Add Node", "You can't add your own node to the list of remote peers.<br /> <a href=\".\">Return to the peers page</a>");
				return;
			}
			if(!this.node.addDarknetConnection(pn)) {
				this.sendErrorPage(ctx, 200, "Failed To Add Node", "We already have the given reference.<br /> <a href=\".\">Return to the peers page</a>");
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
		} else if (request.isPartSet("remove") || (request.isPartSet("submit") && request.getPartAsString("action",25).equals("remove"))) {
			//int hashcode = Integer.decode(request.getParam("node")).intValue();
			
			PeerNode[] peerNodes = node.getDarknetConnections();
			for(int i = 0; i < peerNodes.length; i++) {
				if (request.isPartSet("node_"+peerNodes[i].hashCode())) {
					this.node.removeDarknetConnection(peerNodes[i]);
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
	
	private String idleToString(long now, long idle, int peerNodeStatus) {
		if (idle == -1) {
			return " ";
		}
		long idleSeconds = (now - idle) / 1000;
		if(idleSeconds < 60 && (peerNodeStatus == Node.PEER_NODE_STATUS_CONNECTED || peerNodeStatus == Node.PEER_NODE_STATUS_ROUTING_BACKED_OFF)) {
		  return "0m";
		}
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
