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
import java.util.Hashtable;
import java.util.Enumeration;

import freenet.client.HighLevelSimpleClient;
import freenet.io.comm.PeerParseException;
import freenet.node.FSParseException;
import freenet.node.Node;
import freenet.node.PeerNode;
import freenet.node.Version;
import freenet.support.Bucket;
import freenet.support.HTMLEncoder;
import freenet.support.SimpleFieldSet;

public class DarknetConnectionsToadlet extends Toadlet {

	public class MyComparator implements Comparator {

		public int compare(Object arg0, Object arg1) {
			Object[] row0 = (Object[])arg0;
			Object[] row1 = (Object[])arg1;
			Integer stat0 = (Integer) row0[1];  // 1 = status
			Integer stat1 = (Integer) row1[1];
			int x = stat0.compareTo(stat1);
			if(x != 0) return x;
			String name0 = (String) row0[2];  // 2 = node name
			String name1 = (String) row1[2];
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
		
		// FIXME! We need some nice images
		PeerNode[] peerNodes = node.getDarknetConnections();
		
		long now = System.currentTimeMillis();
		
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
		buf.append("<table class=\"darknet_connections\">\n");
		buf.append("<tr><th>Status</th><th>Name</th><th> <span title=\"Address:Port\" style=\"border-bottom:1px dotted;cursor:help;\">Address</span></th><th>Version</th><th>Location</th><th> <span title=\"Temporarily disconnected. Other node busy? Wait time(s) remaining/total\" style=\"border-bottom:1px dotted;cursor:help;\">Backoff</span></th><th> <span title=\"Number of minutes since the node was last seen in this session\" style=\"border-bottom:1px dotted;cursor:help;\">Idle</span></th><th></th></tr>\n");

		final Integer CONNECTED = new Integer(0);
		final Integer BACKED_OFF = new Integer(1);
		final Integer TOO_NEW = new Integer(2);
		final Integer INCOMPATIBLE = new Integer(3);
		final Integer DISCONNECTED = new Integer(4);
		
		int numberOfConnected = 0;
		int numberOfBackedOff = 0;
		int numberOfTooNew = 0;
		int numberOfIncompatible = 0;
		int numberOfDisconnected = 0;
		
		// Create array
		Object[][] rows = new Object[peerNodes.length][];
		String[][] messageTypesRows = new String[peerNodes.length][];
		for(int i=0;i<peerNodes.length;i++) {
			PeerNode pn = peerNodes[i];
			long routingBackedOffUntil = pn.getRoutingBackedOffUntil();
			boolean routingBackedOffNow = (now < routingBackedOffUntil);
			int backoff = (int)(Math.max(routingBackedOffUntil - now, 0));
			long idle = pn.lastReceivedPacketTime();
			
			// Elements must be HTML encoded.
			Object[] row = new Object[9];  // where [0] is the pn object!
			String[] messageTypesRow = new String[2];
			rows[i] = row;
			
			Object status;
			if(pn.isConnected()) {
				status = CONNECTED;
				if(routingBackedOffNow) {
					status = BACKED_OFF;
				}
			} else if(pn.hasCompletedHandshake() && pn.isVerifiedIncompatibleNewerVersion()) {
				status = TOO_NEW;
			} else if(pn.hasCompletedHandshake() && !Version.checkGoodVersion(pn.getVersion())) {
				status = INCOMPATIBLE;
			} else {
				status = DISCONNECTED;
			}
			
			row[0] = pn;
			row[1] = status;
			row[2] = HTMLEncoder.encode(pn.getName());
			row[3] = pn.getDetectedPeer() != null ? HTMLEncoder.encode(pn.getDetectedPeer().toString()) : "(address unknown)";
			row[4] = HTMLEncoder.encode(pn.getVersion());
			row[5] = new Double(pn.getLocation().getValue());
			row[6] = backoff/1000 + "/" + pn.getRoutingBackoffLength()/1000;
			if (idle == -1) row[7] = " ";
			else row[7] = new Long((now - idle) / 60000);
			row[8] = "<input type=\"checkbox\" name=\"delete_node_"+pn.hashCode()+"\" />";
		}

		// Sort array
		Arrays.sort(rows, new MyComparator());
		
		// Convert status codes into status strings
		for(int i=0;i<rows.length;i++) {
			Object[] row = rows[i];
			Integer x = (Integer) row[1];
			if(x == CONNECTED) {
				row[1] = "<span class=\"peer_connected\">CONNECTED</span>";
				numberOfConnected++;
			}
			else if(x == BACKED_OFF) {
				row[1] = "<span class=\"peer_backedoff\">BACKED OFF</span>";
				numberOfBackedOff++;
			}
			else if(x == TOO_NEW) {
				row[1] = "<span class=\"peer_too_new\">TOO NEW</span>";
				numberOfTooNew++;
			}
			else if(x == INCOMPATIBLE) {
				row[1] = "<span class=\"peer_incompatible\">INCOMPATIBLE</span>";
				numberOfIncompatible++;
			}
			else if(x == DISCONNECTED) {
				row[1] = "<span class=\"peer_disconnected\">DISCONNECTED</span>";
				numberOfDisconnected++;
			}
		}
		
		// Turn array into HTML
		for(int i=0;i<rows.length;i++) {
			Object[] row = rows[i];
			buf.append("<tr>");
			for(int j=1;j<row.length;j++) {  // skip index 0 as it's the PeerNode object
				buf.append("<td>"+row[j]+"</td>");
			}
			buf.append("</tr>\n");
			
			if (path.endsWith("displaymessagetypes.html"))
			{
				buf.append("<tr class=\"messagetypes\"><td colspan=\"8\">\n");
				buf.append("<table class=\"sentmessagetypes\">\n");
				buf.append("<tr><th>Sent Message Type</th><th>Count</th></tr>\n");
				for (Enumeration keys=((PeerNode)row[0]).getLocalNodeSentMessagesToStatistic().keys(); keys.hasMoreElements(); )
				{
					Object curkey = keys.nextElement();
					buf.append("<tr><td>");
					buf.append((String)curkey);
					buf.append("</td><td>");
					buf.append(((Long)((PeerNode)row[0]).getLocalNodeSentMessagesToStatistic().get(curkey)) + "");
					buf.append("</td></tr>\n");
				}
				buf.append("</table>\n");
	
				buf.append("<table class=\"receivedmessagetypes\">\n");
				buf.append("<tr><th>Received Message Type</th><th>Count</th></tr>\n");
				for (Enumeration keys=((PeerNode)row[0]).getLocalNodeReceivedMessagesFromStatistic().keys(); keys.hasMoreElements(); )
				{
					Object curkey = keys.nextElement();
					buf.append("<tr><td>");
					buf.append((String)curkey);
					buf.append("</td><td>");
					buf.append(((Long)((PeerNode)row[0]).getLocalNodeReceivedMessagesFromStatistic().get(curkey)) + "");
					buf.append("</td></tr>\n");
				}
				buf.append("</table>\n");
				buf.append("</td></tr>\n");
			}
		}
		buf.append("</table>\n");
		//
		if (rows.length != 0) {
			buf.append("<table class=\"darknet_connections\">\n");
			buf.append("<tr><td>");
			boolean separatorNeeded = false;
			if (numberOfConnected != 0) {
				buf.append("<span class=\"peer_connected\">CONNECTED: " + numberOfConnected + "</span>");
				separatorNeeded = true;
			}
			if (numberOfBackedOff != 0) {
				if (separatorNeeded)
					buf.append(" | ");
				buf.append("<span class=\"peer_backedoff\">BACKED OFF: " + numberOfBackedOff + "</span>");
				separatorNeeded = true;
			}
			if (numberOfTooNew != 0) {
				if (separatorNeeded)
					buf.append(" | ");
				buf.append("<span class=\"peer_too_new\">TOO NEW: " + numberOfTooNew + "</span>");
				separatorNeeded = true;
			}
			if (numberOfIncompatible != 0) {
				if (separatorNeeded)
					buf.append(" | ");
				buf.append("<span class=\"peer_incompatible\">INCOMPATIBLE: " + numberOfIncompatible + "</span>");
				separatorNeeded = true;
			}
			if (numberOfDisconnected != 0) {
				if (separatorNeeded)
					buf.append(" | ");
				buf.append("<span class=\"peer_disconnected\">DISCONNECTED: " + numberOfDisconnected + "</span>");
				separatorNeeded = true;
			}
			buf.append("</td></tr>\n");
			buf.append("</table>\n");
		}
		//
		buf.append("<input type=\"submit\" name=\"disconnect\" value=\"Disconnect from selected Peers\" />\n");
		buf.append("</form>\n");
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
		buf.append("<textarea name=\"ref\" rows=\"8\" cols=\"74\"></textarea>\n");
		buf.append("<br />\n");
		buf.append("or URL:\n");
		buf.append("<input type=\"text\" name=\"url\" />\n");
		buf.append("<br />\n");
		buf.append("or file:\n");
		buf.append("<input type=\"file\" name=\"reffile\" />\n");
		buf.append("<br />\n");
		buf.append("<input type=\"submit\" name=\"connect\" value=\"Connect\" />\n");
		buf.append("</form>\n");
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
					this.sendErrorPage(ctx, 200, "Failed to Add Node", "Failed to add node: Unable to retrieve node reference from "+urltext+".");
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
				this.sendErrorPage(ctx, 200, "Failed to Add Node", "Failed to add node: Could not detect either a node reference or a URL. Please <a href=\".\">Try again</a>.");
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
				this.sendErrorPage(ctx, 200, "Failed to add node", "Unable to parse the given text: <pre>"+ref+"</pre> as a node reference: "+e+" Please <a href=\".\">Try again</a>.");
				return;
			}
			PeerNode pn;
			try {
				pn = new PeerNode(fs, this.node, false);
			} catch (FSParseException e1) {
				this.sendErrorPage(ctx, 200, "Failed to add node", "Unable to parse the given text: <pre>"+ref+"</pre> as a node reference: "+e1+". Please <a href=\".\">Try again</a>.");
				return;
			} catch (PeerParseException e1) {
				this.sendErrorPage(ctx, 200, "Failed to add node", "Unable to parse the given text: <pre>"+ref+"</pre> as a node reference: "+e1+". Please <a href=\".\">Try again</a>.");
				return;
			}
			if(pn.getIdentity()==node.getIdentity()) {
				this.sendErrorPage(ctx, 200, "Referencing to self", "You can't add your own node to the list of remote peers. Return to the connections page <a href=\".\">here</a>.");
				return;
			}
			if(!this.node.addDarknetConnection(pn)) {
				this.sendErrorPage(ctx, 200, "Failed to add node", "We already have the given reference. Return to the connections page <a href=\".\">here</a>.");
				return;
			}
		} else if (request.isPartSet("disconnect")) {
			//int hashcode = Integer.decode(request.getParam("node")).intValue();
			
			PeerNode[] peerNodes = node.getDarknetConnections();
			for(int i = 0; i < peerNodes.length; i++) {
				if (request.isPartSet("delete_node_"+peerNodes[i].hashCode())) {
					this.node.removeDarknetConnection(peerNodes[i]);
				}
			}
		}
		this.handleGet(uri, ctx);
	}

}
