package freenet.clients.http;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Arrays;
import java.util.Comparator;

import freenet.client.HighLevelSimpleClient;
import freenet.node.Node;
import freenet.node.PeerNode;
import freenet.node.Version;
import freenet.node.FSParseException;
import freenet.io.comm.PeerParseException;
import freenet.pluginmanager.HTTPRequest;
import freenet.support.HTMLEncoder;
import freenet.support.Bucket;
import freenet.support.BucketTools;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

public class DarknetConnectionsToadlet extends Toadlet {

	public class MyComparator implements Comparator {

		public int compare(Object arg0, Object arg1) {
			Object[] row0 = (Object[])arg0;
			Object[] row1 = (Object[])arg1;
			Integer stat0 = (Integer) row0[0];
			Integer stat1 = (Integer) row1[0];
			int x = stat0.compareTo(stat1);
			if(x != 0) return x;
			String name0 = (String) row0[1];
			String name1 = (String) row1[1];
			return name0.toLowerCase().compareTo(name1.toLowerCase());
		}

	}

	Node node;
	
	protected DarknetConnectionsToadlet(Node n, HighLevelSimpleClient client) {
		super(client);
		this.node = n;
	}

	public String supportedMethods() {
		return "GET, POST";
	}

	public void handleGet(URI uri, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		StringBuffer buf = new StringBuffer();
		
		HTTPRequest request = new HTTPRequest(uri);
		ctx.getPageMaker().makeHead(buf, "Darknet Connections");
		
		// our reference
		buf.append("<div class=\"infobox\">\n");
		buf.append("<h2>My Reference</h2>\n");
		buf.append("<pre>\n");
		buf.append(this.node.exportFieldSet());
		buf.append("</pre>\n");
		buf.append("</div>\n");
		
		// FIXME! We need some nice images
		PeerNode[] peerNodes = node.getDarknetConnections();
		
		long now = System.currentTimeMillis();
		
		buf.append("<div class=\"infobox\">\n");
		buf.append("<h2>My Connections</h2>\n");
		buf.append("<table class=\"darknet_connections\">\n");
		buf.append("<tr><th>Status</th><th>Name</th><th>Address</th><th>Version</th><th>Location</th><th>Backoff</th><th>Backoff length</th><th></th></tr>\n");

		final Integer CONNECTED = new Integer(0);
		final Integer BACKED_OFF = new Integer(1);
		final Integer INCOMPATIBLE = new Integer(2);
		final Integer DISCONNECTED = new Integer(3);
		
		// Create array
		Object[][] rows = new Object[peerNodes.length][];
		for(int i=0;i<peerNodes.length;i++) {
			PeerNode pn = peerNodes[i];
			long backedOffUntil = pn.getBackedOffUntil();
			int backoffLength = pn.getBackoffLength();
			boolean backedOffNow = (now < backedOffUntil);
			
			Object[] row = new Object[8];
			rows[i] = row;
			
			Object status;
			if(pn.isConnected()) {
				status = CONNECTED;
				if(backedOffNow) {
					status = BACKED_OFF;
				}
			} else if(pn.hasCompletedHandshake() && !Version.checkGoodVersion(pn.getVersion())) {
				status = INCOMPATIBLE;
			} else {
				status = DISCONNECTED;
			}
			
			row[0] = status;
			row[1] = HTMLEncoder.encode(pn.getName());
			row[2] = pn.getDetectedPeer() != null ? pn.getDetectedPeer().toString() : "(address unknown)";
			row[3] = pn.getVersion();
			row[4] = new Double(pn.getLocation().getValue());
			row[5] = new Long(Math.max(backedOffUntil - now, 0));
			row[6] = new Long(backoffLength);
			row[7] = new String("<form action=\".\" method=\"post\">\n"
					+"<div>"
					+"<input type=\"hidden\" name=\"node\" value=\""+pn.hashCode()+"\" />"
					+"<input type=\"submit\" name=\"disconnect\" value=\"Disconnect\" />\n"
					+"</div>\n"
					+"</form>\n");
		}

		// Sort array
		Arrays.sort(rows, new MyComparator());
		
		// Convert status codes into status strings
		for(int i=0;i<rows.length;i++) {
			Object[] row = rows[i];
			Integer x = (Integer) row[0];
			if(x == CONNECTED) row[0] = "<span class=\"peer_connected\">CONNECTED</span>";
			else if(x == BACKED_OFF) row[0] = "<span class=\"peer_backedoff\">BACKED OFF</span>";
			else if(x == INCOMPATIBLE) row[0] = "<span class=\"peer_incompatable\">INCOMPATIBLE</span>";
			else if(x == DISCONNECTED) row[0] = "<span class=\"peer_disconnected\">DISCONNECTED</span>";
		}
		
		// Turn array into HTML
		for(int i=0;i<rows.length;i++) {
			Object[] row = rows[i];
			buf.append("<tr><td>");
			for(int j=0;j<row.length;j++) {
				buf.append(row[j]);
				if(j != row.length-1)
					buf.append("</td><td>");
			}
			buf.append("</td></tr>");
		}
		buf.append("</table>");
		buf.append("</div>");
		
		// new connection box
		buf.append("<form action=\".\" method=\"post\">\n");
		buf.append("<div class=\"infobox\">\n");
		buf.append("<h2>\n");
		buf.append("Connect to another node\n");
		buf.append("</h2>\n");
		buf.append("Reference:<br />\n");
		buf.append("<textarea name=\"ref\" rows=\"8\" cols=\"74\"></textarea>\n");
		buf.append("<br />\n");
		buf.append("or URL:\n");
		buf.append("<input type=\"text\" name=\"url\" style=\"width: 100%\"/>\n");
		buf.append("<br />\n");
		buf.append("<input type=\"submit\" name=\"connect\" value=\"Connect\" />\n");
		buf.append("</div>\n");
		buf.append("</form>\n");
		
		ctx.getPageMaker().makeTail(buf);
		
		this.writeReply(ctx, 200, "text/html", "OK", buf.toString());
	}
	
	public void handlePost(URI uri, Bucket data, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		if(data.size() > 1024*1024) {
			this.writeReply(ctx, 400, "text/plain", "Too big", "Too much data, darknet toadlet limited to 1MB");
			return;
		}
		byte[] d = BucketTools.toByteArray(data);
		String s = new String(d, "us-ascii");
		HTTPRequest request;
		try {
			request = new HTTPRequest("/", s);
		} catch (URISyntaxException e) {
			Logger.error(this, "Impossible: "+e, e);
			return;
		}
		
		if (request.getParam("connect").length() > 0) {
			// connect to a new node
			String urltext = request.getParam("url");
			urltext = urltext.trim();
			String reftext = request.getParam("ref");
			reftext = reftext.trim();
			
			String ref = new String("");
			
			if (urltext.length() > 0) {
				// fetch reference from a URL
				try {
					URL url = new URL(urltext);
					URLConnection uc = url.openConnection();
					BufferedReader in = new BufferedReader(
							new InputStreamReader(uc.getInputStream()));
					String line;
					while ( (line = in.readLine()) != null) {
						ref += line+"\n";
					}
				} catch (Exception e) {
					this.sendErrorPage(ctx, 200, "Failed to add node", "Unable to retrieve node reference from "+urltext+".");
				}
			} else if (reftext.length() > 0) {
				// read directly from post data
				// this slightly scary looking regexp chops any extra characters off the beginning or ends of lines and removes extra line breaks
				ref = reftext.replaceAll(".*?((?:[\\w,\\.]+\\=[\\w,\\.:\\-_]+)|(?:End)).*(?:\\r?\\n)*", "$1\n");
			} else {
				this.sendErrorPage(ctx, 200, "Failed to add node", "Could not detect either a node reference or a URL. Please <a href=\".\">Try again</a>.");
				return;
			}
			// we have a node reference in ref
			SimpleFieldSet fs;
			
			try {
				fs = new SimpleFieldSet(ref, false);
			} catch (IOException e) {
				this.sendErrorPage(ctx, 200, "Failed to add node", "Unable to parse the given text: <pre>"+reftext+"</pre> as a node reference. Please <a href=\".\">Try again</a>.");
				return;
			}
			PeerNode pn;
			try {
				pn = new PeerNode(fs, this.node);
			} catch (FSParseException e1) {
				this.sendErrorPage(ctx, 200, "Failed to add node", "Unable to parse the given text: <pre>"+reftext+"</pre> as a node reference. Please <a href=\".\">Try again</a>.");
				return;
			} catch (PeerParseException e1) {
				this.sendErrorPage(ctx, 200, "Failed to add node", "Unable to parse the given text: <pre>"+reftext+"</pre> as a node reference. Please <a href=\".\">Try again</a>.");
				return;
			}
			if(!this.node.addDarknetConnection(pn)) {
				this.sendErrorPage(ctx, 200, "Failed to add node", "Unable to add the given reference as a peer. Please <a href=\".\">Try again</a>.");
			}
		} else if (request.getParam("disconnect").length() > 0) {
			int hashcode = Integer.decode(request.getParam("node")).intValue();
			
			PeerNode[] peerNodes = node.getDarknetConnections();
			for(int i = 0; i < peerNodes.length; i++) {
				if (hashcode == peerNodes[i].hashCode()) {
					this.node.removeDarknetConnection(peerNodes[i]);
					break;
				}
			}
		}
		this.handleGet(uri, ctx);
	}
}
