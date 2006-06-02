package freenet.clients.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;

import freenet.client.HighLevelSimpleClient;
import freenet.io.comm.PeerParseException;
import freenet.node.FSParseException;
import freenet.node.Node;
import freenet.node.PeerNode;
import freenet.node.Version;
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

		ctx.getPageMaker().makeHead(buf, "Darknet Connections");
		
		// FIXME! We need some nice images
		PeerNode[] peerNodes = node.getDarknetConnections();
		
		long now = System.currentTimeMillis();
		
		node.alerts.toSummaryHtml(buf);
		
		/* node status values */
		int bwlimitDelayTime = (int) node.getBwlimitDelayTime();
		int nodeAveragePingTime = (int) node.getNodeAveragePingTime();
		
		/* gather connection statistics */
		int numberOfConnected = 0;
		int numberOfBackedOff = 0;
		int numberOfTooNew = 0;
		int numberOfTooOld = 0;
		int numberOfDisconnected = 0;
		for (int peerIndex = 0, peerCount = peerNodes.length; peerIndex < peerCount; peerIndex++) {
			int peerStatus = peerNodes[peerIndex].getPeerNodeStatus();
			if (peerStatus == Node.PEER_NODE_STATUS_CONNECTED) {
				numberOfConnected++;
			} else if (peerStatus == Node.PEER_NODE_STATUS_ROUTING_BACKED_OFF) {
				numberOfBackedOff++;
			} else if (peerStatus == Node.PEER_NODE_STATUS_TOO_NEW) {
				numberOfTooNew++;
			} else if (peerStatus == Node.PEER_NODE_STATUS_TOO_OLD) {
				numberOfTooOld++;
			} else if (peerStatus == Node.PEER_NODE_STATUS_DISCONNECTED) {
				numberOfDisconnected++;
			}
		}
		
		buf.append("<table class=\"column\"><tr><td class=\"first\">");
		
		/* node status overview box */
		buf.append("<div class=\"infobox\">");
		buf.append("<div class=\"infobox-header\">Node status overview</div>");
		buf.append("<div class=\"infobox-content\">");
		buf.append("bwlimitDelayTime: " + bwlimitDelayTime + "ms<br/>");
		buf.append("nodeAveragePingTime: " + nodeAveragePingTime + "ms<br/>");
		buf.append("</div>");
		buf.append("</div>\n");
		
		buf.append("</td><td class=\"last\">");
		
		buf.append("<div class=\"infobox\">");
		buf.append("<div class=\"infobox-header\">Connection statistics</div>");
		buf.append("<div class=\"infobox-content\">");
		if (numberOfConnected > 0) {
			buf.append("<span class=\"peer_connected\">Connected: " + numberOfConnected + "</span><br/>");
		}
		if (numberOfBackedOff > 0) {
			buf.append("<span class=\"peer_backedoff\">Backed off: " + numberOfBackedOff + "</span><br/>");
		}
		if (numberOfTooNew > 0) {
			buf.append("<span class=\"peer_too_new\">Too new: " + numberOfTooNew + "</span><br/>");
		}
		if (numberOfTooOld > 0) {
			buf.append("<span class=\"peer_too_old\">Too old: " + numberOfTooOld + "</span><br/>");
		}
		if (numberOfDisconnected > 0) {
			buf.append("<span class=\"peer_disconnected\">Disconnected: " + numberOfDisconnected + "</span><br/>");
		}
		buf.append("</div>");
		buf.append("</div>\n");
		
		buf.append("</td></tr></table>\n");
		
		buf.append("<div class=\"infobox infobox-normal\">\n");
		buf.append("<div class=\"infobox-header\">\n");
		buf.append("My Connections");
		if (!path.endsWith("displaymessagetypes.html"))
		{
			buf.append(" <a href=\"displaymessagetypes.html\">(more detailed)</a>");
		}
		buf.append("</div>\n");
		buf.append("<div class=\"infobox-content\">\n");
		buf.append("<form action=\".\" method=\"post\" enctype=\"multipart/form-data\">\n");
		StringBuffer buf2 = new StringBuffer(1024);
		buf2.append("<table class=\"darknet_connections\">\n");
		buf2.append("<tr><th></th><th>Status</th><th>Name</th><th><span title=\"Address:Port\" style=\"border-bottom:1px dotted;cursor:help;\">Address</span></th><th>Version</th><th>Location</th><th><span title=\"Temporarily disconnected. Other node busy? Wait time(s) remaining/total\" style=\"border-bottom:1px dotted;cursor:help;\">Backoff</span></th><th><span title=\"Number of minutes since the node was last seen in this session\" style=\"border-bottom:1px dotted;cursor:help;\">Idle</span></th></tr>\n");
		
		if (peerNodes.length == 0) {
			buf2.append("<tr><td colspan=\"8\">No connections so far</td></tr>\n");
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
				boolean routingBackedOffNow = (now < routingBackedOffUntil);
				int backoff = (int)(Math.max(routingBackedOffUntil - now, 0));
				// Don't list the backoff as zero before it's actually zero
				if(backoff > 0 && backoff < 1000 )
					backoff = 1000;
				long idle = pn.lastReceivedPacketTime();
				
				// Elements must be HTML encoded.
				Object[] row = new Object[10];  // where [0] is the pn object and 9 is the node name only for sorting!
				rows[i] = row;
				
				Object status = new Integer(pn.getPeerNodeStatus());
				String lastBackoffReasonOutputString = "/";
				String backoffReason = pn.getLastBackoffReason();
				if( backoffReason != null ) {
					lastBackoffReasonOutputString = "/"+backoffReason;
				}
				String avgPingTimeString = "";
				if(pn.isConnected())
					avgPingTimeString = " ("+(int) pn.averagePingTime()+"ms)";
				String VersionPrefixString = "";
				String VersionSuffixString = "";
				if(pn.publicInvalidVersion() || pn.publicReverseInvalidVersion()) {
					VersionPrefixString = "<span class=\"peer_version_problem\">";
					VersionSuffixString = "</span>";
				}
				String NamePrefixString = "";
				String NameSuffixString = "";
				if(pn.isConnected()) {
				  NamePrefixString = "<a href=\"/send_n2ntm/?peernode_hashcode="+pn.hashCode()+"\">";
				  NameSuffixString = "</a>";
				}
				
				row[0] = pn;
				row[1] = "<input type=\"checkbox\" name=\"delete_node_"+pn.hashCode()+"\" />";
				row[2] = status;
				row[3] = NamePrefixString+HTMLEncoder.encode(pn.getName())+NameSuffixString;
				row[4] = ( pn.getDetectedPeer() != null ? HTMLEncoder.encode(pn.getDetectedPeer().toString()) : "(address unknown)" ) + avgPingTimeString;
				row[5] = VersionPrefixString+HTMLEncoder.encode(pn.getVersion())+VersionSuffixString;
				row[6] = new Double(pn.getLocation().getValue());
				row[7] = backoff/1000 + "/" + pn.getRoutingBackoffLength()/1000+lastBackoffReasonOutputString;
				if (idle == -1) row[8] = " ";
				else row[8] = new Long((now - idle) / 60000);
				row[9] = HTMLEncoder.encode(pn.getName());
			}
	
			// Sort array
			Arrays.sort(rows, new MyComparator());
			
			// Convert status codes into status strings
			for(int i=0;i<rows.length;i++) {
				Object[] row = rows[i];
				int x = ((Integer) row[2]).intValue();
				if(x == Node.PEER_NODE_STATUS_CONNECTED) {
					row[2] = "<span class=\"peer_connected\">CONNECTED</span>";
				}
				else if(x == Node.PEER_NODE_STATUS_ROUTING_BACKED_OFF) {
					row[2] = "<span class=\"peer_backedoff\">BACKED OFF</span>";
				}
				else if(x == Node.PEER_NODE_STATUS_TOO_NEW) {
					row[2] = "<span class=\"peer_too_new\">TOO NEW</span>";
				}
				else if(x == Node.PEER_NODE_STATUS_TOO_OLD) {
					row[2] = "<span class=\"peer_too_old\">TOO OLD</span>";
				}
				else if(x == Node.PEER_NODE_STATUS_DISCONNECTED) {
					row[2] = "<span class=\"peer_disconnected\">DISCONNECTED</span>";
				}
			}
			
			// Turn array into HTML
			for(int i=0;i<rows.length;i++) {
				Object[] row = rows[i];
				buf2.append("<tr>");
				for(int j=1;j<row.length;j++) {  // skip index 0 as it's the PeerNode object
				  if(j == 9) // skip index 9 as it's used for sorting purposes only
				    continue;
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
			buf.append("<input type=\"submit\" name=\"disconnect\" value=\"Disconnect from selected peers\" />\n");
			buf.append("</form>\n");
		}
		buf.append("</div>\n");
		buf.append("</div>\n");
		
		// new connection box
		buf.append("<div class=\"infobox infobox-normal\">\n");
		buf.append("<div class=\"infobox-header\">\n");
		buf.append("Connect to another node\n");
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
		buf.append("<input type=\"submit\" name=\"connect\" value=\"Connect\" />\n");
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
		
		if (request.isPartSet("connect")) {
			// connect to a new node
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
				this.sendErrorPage(ctx, 200, "Failed To Add Node", "You can't add your own node to the list of remote peers.<br /> <a href=\".\">Return to the connections page</a>");
				return;
			}
			if(!this.node.addDarknetConnection(pn)) {
				this.sendErrorPage(ctx, 200, "Failed To Add Node", "We already have the given reference.<br /> <a href=\".\">Return to the connections page</a>");
				return;
			}
			
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/darknet/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		} else if (request.isPartSet("disconnect")) {
			//int hashcode = Integer.decode(request.getParam("node")).intValue();
			
			PeerNode[] peerNodes = node.getDarknetConnections();
			for(int i = 0; i < peerNodes.length; i++) {
				if (request.isPartSet("delete_node_"+peerNodes[i].hashCode())) {
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
}
