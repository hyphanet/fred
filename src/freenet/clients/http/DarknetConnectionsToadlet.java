package freenet.clients.http;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Comparator;

import freenet.client.HighLevelSimpleClient;
import freenet.node.Node;
import freenet.node.PeerNode;
import freenet.node.Version;
import freenet.pluginmanager.HTTPRequest;
import freenet.support.HTMLEncoder;

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
		// FIXME! 1) Probably would be better to use CSS
		// FIXME! 2) We need some nice images
		PeerNode[] peerNodes = node.getDarknetConnections();
		
		long now = System.currentTimeMillis();
		
		buf.append("<table border=\"0\">\n");
		buf.append("<tr><th>Status</th><th>Name</th><th>Address</th><th>Version</th><th>Location</th><th>Backoff</th><th>Backoff length</th></tr>\n");

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
			
			Object[] row = new Object[7];
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
			row[2] = pn.getDetectedPeer().toString();
			row[3] = pn.getVersion();
			row[4] = new Double(pn.getLocation().getValue());
			row[5] = new Long(Math.max(backedOffUntil - now, 0));
			row[6] = new Long(backoffLength);
		}

		// Sort array
		Arrays.sort(rows, new MyComparator());
		
		// Convert status codes into status strings
		for(int i=0;i<rows.length;i++) {
			Object[] row = rows[i];
			Integer x = (Integer) row[0];
			if(x == CONNECTED) row[0] = "<font color=\"green\">CONNECTED</font>";
			else if(x == BACKED_OFF) row[0] = "<font color=\"red\">BACKED OFF</font>";
			else if(x == INCOMPATIBLE) row[0] = "<font color=\"blue\">INCOMPATIBLE</font>";
			else if(x == DISCONNECTED) row[0] = "<font color=\"black\">DISCONNECTED</font>";
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
		
		ctx.getPageMaker().makeTail(buf);
		
		this.writeReply(ctx, 200, "text/html", "OK", buf.toString());
	}
	
}
