/* Copyright 2007 Freenet Project Inc.
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package freenet.clients.http;

import java.io.IOException;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.io.AddressTracker;
import freenet.io.AddressTrackerItem;
import freenet.io.InetAddressAddressTrackerItem;
import freenet.io.PeerAddressTrackerItem;
import freenet.io.AddressTrackerItem.Gap;
import freenet.io.comm.UdpSocketHandler;
import freenet.l10n.NodeL10n;
import freenet.node.FSParseException;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.support.HTMLNode;
import freenet.support.SimpleFieldSet;
import freenet.support.TimeUtil;
import freenet.support.api.HTTPRequest;

/**
 * Toadlet displaying information on the node's connectivity status.
 * Eventually this will include all information gathered by the node on its
 * connectivity from plugins, local IP detection, packet monitoring etc.
 * For the moment it's just a dump of the AddressTracker.
 * @author toad
 */
public class ConnectivityToadlet extends Toadlet {
	
	private final Node node;
	private final NodeClientCore core;

	protected ConnectivityToadlet(HighLevelSimpleClient client, Node node, NodeClientCore core) {
		super(client);
		this.node = node;
		this.core = core;
	}

	public void handleMethodGET(URI uri, final HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		PageMaker pageMaker = ctx.getPageMaker();
		
		PageNode page = pageMaker.getPageNode(NodeL10n.getBase().getString("ConnectivityToadlet.title"), ctx);
		HTMLNode pageNode = page.outer;
		HTMLNode contentNode = page.content;

		/* add alert summary box */
		if(ctx.isAllowedFullAccess())
			contentNode.addChild(core.alerts.createSummary());

		// our ports
		HTMLNode portInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		portInfobox.addChild("div", "class", "infobox-header", l10nConn("nodePortsTitle"));
		HTMLNode portInfoboxContent = portInfobox.addChild("div", "class", "infobox-content");
		HTMLNode portInfoList = portInfoboxContent.addChild("ul");
		SimpleFieldSet fproxyConfig = node.config.get("fproxy").exportFieldSet(true);
		SimpleFieldSet fcpConfig = node.config.get("fcp").exportFieldSet(true);
		SimpleFieldSet tmciConfig = node.config.get("console").exportFieldSet(true);
		portInfoList.addChild("li", NodeL10n.getBase().getString("DarknetConnectionsToadlet.darknetFnpPort", new String[] { "port" }, new String[] { Integer.toString(node.getFNPPort()) }));
		int opennetPort = node.getOpennetFNPPort();
		if(opennetPort > 0)
			portInfoList.addChild("li", NodeL10n.getBase().getString("DarknetConnectionsToadlet.opennetFnpPort", new String[] { "port" }, new String[] { Integer.toString(opennetPort) }));
		try {
			if(fproxyConfig.getBoolean("enabled", false)) {
				portInfoList.addChild("li", NodeL10n.getBase().getString("DarknetConnectionsToadlet.fproxyPort", new String[] { "port" }, new String[] { Integer.toString(fproxyConfig.getInt("port")) }));
			} else {
				portInfoList.addChild("li", l10nConn("fproxyDisabled"));
			}
			if(fcpConfig.getBoolean("enabled", false)) {
				portInfoList.addChild("li", NodeL10n.getBase().getString("DarknetConnectionsToadlet.fcpPort", new String[] { "port" }, new String[] { Integer.toString(fcpConfig.getInt("port")) }));
			} else {
				portInfoList.addChild("li", l10nConn("fcpDisabled"));
			}
			if(tmciConfig.getBoolean("enabled", false)) {
				portInfoList.addChild("li", NodeL10n.getBase().getString("DarknetConnectionsToadlet.tmciPort", new String[] { "port" }, new String[] { Integer.toString(tmciConfig.getInt("port")) }));
			} else {
				portInfoList.addChild("li", l10nConn("tmciDisabled"));
			}
		} catch (FSParseException e) {
			// ignore
		}
		
		// Add connection type box.
		
		node.ipDetector.addConnectionTypeBox(contentNode);
		
		UdpSocketHandler[] handlers = node.getPacketSocketHandlers();
		
		HTMLNode summaryContent = pageMaker.getInfobox("#", NodeL10n.getBase().getString("ConnectivityToadlet.summaryTitle"), contentNode, "connectivity-summary", true);
		
		HTMLNode table = summaryContent.addChild("table", "border", "0");
		
		for(UdpSocketHandler handler: handlers) {
			AddressTracker tracker = handler.getAddressTracker();
			HTMLNode row = table.addChild("tr");
			row.addChild("td", handler.getTitle());
			row.addChild("td", AddressTracker.statusString(tracker.getPortForwardStatus()));
		}
		
		if(ctx.getContainer().isAdvancedModeEnabled()) {
		
		// One box per port
		
		String noreply = l10n("noreply");
		String local = l10n("local");
		String remote = l10n("remote");
		long now = System.currentTimeMillis();
		
		for(UdpSocketHandler handler: handlers) {
			// Peers
			AddressTracker tracker = handler.getAddressTracker();
			HTMLNode portsContent = pageMaker.getInfobox("#", NodeL10n.getBase().getString("ConnectivityToadlet.byPortTitle", new String[] { "port", "status", "tunnelLength" }, new String[] { handler.getTitle(), AddressTracker.statusString(tracker.getPortForwardStatus()), TimeUtil.formatTime(tracker.getLongestSendReceiveGap()) }), contentNode, "connectivity-port", false);
			PeerAddressTrackerItem[] items = tracker.getPeerAddressTrackerItems();
			table = portsContent.addChild("table");
			HTMLNode row = table.addChild("tr");
			row.addChild("th", l10n("addressTitle"));
			row.addChild("th", l10n("sentReceivedTitle"));
			row.addChild("th", l10n("localRemoteTitle"));
			row.addChild("th", l10n("firstSendLeadTime"));
			row.addChild("th", l10n("firstReceiveLeadTime"));
			for(int j=0;j<AddressTrackerItem.TRACK_GAPS;j++) {
				row.addChild("th", " "); // FIXME is <th/> valid??
			}
			for(PeerAddressTrackerItem item: items) {
				row = table.addChild("tr");
				// Address
				row.addChild("td", item.peer.toString());
				// Sent/received packets
				row.addChild("td", item.packetsSent() + "/ " + item.packetsReceived());
				// Initiator: local/remote FIXME something more graphical e.g. colored cells
				row.addChild("td", item.packetsReceived() == 0 ? noreply :
						(item.weSentFirst() ? local : remote));
				// Lead in time to first packet sent
				row.addChild("td", TimeUtil.formatTime(item.timeFromStartupToFirstSentPacket()));
				// Lead in time to first packet received
				row.addChild("td", TimeUtil.formatTime(item.timeFromStartupToFirstReceivedPacket()));
				Gap[] gaps = item.getGaps();
				for(int k=0;k<AddressTrackerItem.TRACK_GAPS;k++) {
					row.addChild("td", gaps[k].receivedPacketAt == 0 ? "" : 
						(TimeUtil.formatTime(gaps[k].gapLength)+" @ "+TimeUtil.formatTime(now - gaps[k].receivedPacketAt)+" ago" /* fixme l10n */));
				}
			}

			// IPs
			portsContent = pageMaker.getInfobox("#", NodeL10n.getBase().getString("ConnectivityToadlet.byIPTitle", new String[] { "ip", "status", "tunnelLength" }, new String[] { handler.getTitle(), AddressTracker.statusString(tracker.getPortForwardStatus()), TimeUtil.formatTime(tracker.getLongestSendReceiveGap()) }), contentNode, "connectivity-ip", false);
			InetAddressAddressTrackerItem[] ipItems = tracker.getInetAddressTrackerItems();
			table = portsContent.addChild("table");
			row = table.addChild("tr");
			row.addChild("th", l10n("addressTitle"));
			row.addChild("th", l10n("sentReceivedTitle"));
			row.addChild("th", l10n("localRemoteTitle"));
			row.addChild("th", l10n("firstSendLeadTime"));
			row.addChild("th", l10n("firstReceiveLeadTime"));
			for(int j=0;j<AddressTrackerItem.TRACK_GAPS;j++) {
				row.addChild("th", " "); // FIXME is <th/> valid??
			}
			for(InetAddressAddressTrackerItem item: ipItems) {
				row = table.addChild("tr");
				// Address
				row.addChild("td", item.addr.toString());
				// Sent/received packets
				row.addChild("td", item.packetsSent() + "/ " + item.packetsReceived());
				// Initiator: local/remote FIXME something more graphical e.g. colored cells
				row.addChild("td", item.packetsReceived() == 0 ? noreply :
						(item.weSentFirst() ? local : remote));
				// Lead in time to first packet sent
				row.addChild("td", TimeUtil.formatTime(item.timeFromStartupToFirstSentPacket()));
				// Lead in time to first packet received
				row.addChild("td", TimeUtil.formatTime(item.timeFromStartupToFirstReceivedPacket()));
				Gap[] gaps = item.getGaps();
				for(int k=0;k<AddressTrackerItem.TRACK_GAPS;k++) {
					row.addChild("td", gaps[k].receivedPacketAt == 0 ? "" : 
						(TimeUtil.formatTime(gaps[k].gapLength)+" @ "+TimeUtil.formatTime(now - gaps[k].receivedPacketAt)+" ago" /* fixme l10n */));
				}
			}

		}
		
		}
		
		writeHTMLReply(ctx, 200, "OK", pageNode.generate());
	}
	
	private String l10nConn(String string) {
		return NodeL10n.getBase().getString("DarknetConnectionsToadlet."+string);
	}

	private String l10n(String key) {
		return NodeL10n.getBase().getString("ConnectivityToadlet."+key);
	}

	public static final String PATH = "/connectivity/";
	
	@Override
	public String path() {
		return PATH;
	}
}
