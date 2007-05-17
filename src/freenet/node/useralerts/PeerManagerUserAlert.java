/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.useralerts;

import freenet.l10n.L10n;
import freenet.node.NodeStats;
import freenet.support.HTMLNode;

public class PeerManagerUserAlert implements UserAlert {

	final NodeStats n;
	public int conns = 0;
	public int peers = 0;
	public int neverConn = 0;
	boolean isValid=true;
	int bwlimitDelayTime = 1;
	int nodeAveragePingTime = 1;
	long oldestNeverConnectedPeerAge = 0;
	
	/** How many connected peers we need to not get alert about not enough */
	static final int MIN_CONN_ALERT_THRESHOLD = 3;
	
	/** How many connected peers we can have without getting alerted about too many */
	static final int MAX_CONN_ALERT_THRESHOLD = 30;
	
	/** How many disconnected peers we can have without getting alerted about too many */
	static final int MAX_DISCONN_PEER_ALERT_THRESHOLD = 50;
	
	/** How many never-connected peers can we have without getting alerted about too many */
	static final int MAX_NEVER_CONNECTED_PEER_ALERT_THRESHOLD = 5;
	
	/** How many peers we can have without getting alerted about too many */
	static final int MAX_PEER_ALERT_THRESHOLD = 100;
	
	/** How high can oldestNeverConnectedPeerAge be before we alert (in milliseconds)*/
	static final long MAX_OLDEST_NEVER_CONNECTED_PEER_AGE_ALERT_THRESHOLD = ((long) 2)*7*24*60*60*1000;  // 2 weeks
	
	public PeerManagerUserAlert(NodeStats n) {
		this.n = n;
	}
	
	public boolean userCanDismiss() {
		return false;
	}

	public String getTitle() {
		if(peers == 0)
			return l10n("noPeersTitle");
		if(conns == 0)
			return l10n("noConnsTitle");
		if(conns < MIN_CONN_ALERT_THRESHOLD)
			return l10n("onlyFewConnsTitle", "count", Integer.toString(conns));
		if(neverConn > MAX_NEVER_CONNECTED_PEER_ALERT_THRESHOLD)
			return l10n("tooManyNeverConnectedTitle");
		if((peers - conns) > MAX_DISCONN_PEER_ALERT_THRESHOLD)
			return l10n("tooManyDisconnectedTitle");
		if(conns > MAX_CONN_ALERT_THRESHOLD)
			return l10n("tooManyConnsTitle");
		if(peers > MAX_PEER_ALERT_THRESHOLD)
			return l10n("tooManyPeersTitle");
		if(n.bwlimitDelayAlertRelevant && (bwlimitDelayTime > NodeStats.MAX_BWLIMIT_DELAY_TIME_ALERT_THRESHOLD))
			return l10n("tooHighBwlimitDelayTimeTitle");
		if(n.nodeAveragePingAlertRelevant && (nodeAveragePingTime > NodeStats.MAX_NODE_AVERAGE_PING_TIME_ALERT_THRESHOLD))
			return l10n("tooHighPingTimeTitle");
		if(oldestNeverConnectedPeerAge > MAX_OLDEST_NEVER_CONNECTED_PEER_AGE_ALERT_THRESHOLD)
			return l10n("tooOldNeverConnectedPeersTitle");
		else throw new IllegalArgumentException("Not valid");
	}
	
	private String l10n(String key, String pattern, String value) {
		return L10n.getString("PeerManagerUserAlert."+key, pattern, value);
	}

	private String l10n(String key, String[] pattern, String[] value) {
		return L10n.getString("PeerManagerUserAlert."+key, pattern, value);
	}

	private String l10n(String key) {
		return L10n.getString("PeerManagerUserAlert."+key);
	}

	public String getText() {
		String s;
		int disconnected = peers - conns;
		if(peers == 0) {
			if(n.isTestnetEnabled())
				return l10n("noPeersTestnet");
			else
				return l10n("noPeersDarknet"); 
		} else if(conns == 0) {
			return l10n("noConns");
		} else if(conns == 1) {
			return l10n("oneConn");
		} else if(conns == 2) {
			return l10n("twoConns");
		} else if(neverConn > MAX_NEVER_CONNECTED_PEER_ALERT_THRESHOLD) {
			s = l10n("tooManyNeverConnected", "count", Integer.toString(neverConn));
		} else if((peers - conns) > MAX_DISCONN_PEER_ALERT_THRESHOLD){
			s = l10n("tooManyDisconnected", new String[] { "count", "max" }, 
					new String[] { Integer.toString(disconnected), Integer.toString(MAX_DISCONN_PEER_ALERT_THRESHOLD)});
		} else if(conns > MAX_CONN_ALERT_THRESHOLD) {
			s = l10n("tooManyConns", new String[] { "count", "max" }, 
					new String[] { Integer.toString(conns), Integer.toString(MAX_CONN_ALERT_THRESHOLD)});
		} else if(peers > MAX_PEER_ALERT_THRESHOLD) {
			s = l10n("tooManyPeers", new String[] { "count", "max" },
					new String[] { Integer.toString(peers), Integer.toString(MAX_PEER_ALERT_THRESHOLD)});
		} else if(n.bwlimitDelayAlertRelevant && (bwlimitDelayTime > NodeStats.MAX_BWLIMIT_DELAY_TIME_ALERT_THRESHOLD)) {
			s = l10n("tooHighBwlimitDelayTime", new String[] { "delay", "max" },
					new String[] { Integer.toString(bwlimitDelayTime), Long.toString(NodeStats.MAX_BWLIMIT_DELAY_TIME_ALERT_THRESHOLD)});
			// FIXME I'm not convinced about the next one!
		} else if(n.nodeAveragePingAlertRelevant && (nodeAveragePingTime > NodeStats.MAX_NODE_AVERAGE_PING_TIME_ALERT_THRESHOLD)) {
			s = l10n("tooHighPingTime", new String[] { "ping", "max" },
					new String[] { Integer.toString(nodeAveragePingTime), Long.toString(NodeStats.MAX_NODE_AVERAGE_PING_TIME_ALERT_THRESHOLD) });
		} else if(oldestNeverConnectedPeerAge > MAX_OLDEST_NEVER_CONNECTED_PEER_AGE_ALERT_THRESHOLD) {
			return l10n("tooOldNeverConnectedPeers");
		} else throw new IllegalArgumentException("Not valid");
		return s;
	}

	static final public String replace(String text, String find, String replace) {
		return replaceCareful(text, find, replace);
	}
	
	static public String replaceAll(String text, String find, String replace) {
		int i;
		while((i = text.indexOf(find)) >= 0) {
			text = text.substring(0, i) + replace + text.substring(i + find.length());
		}
		return text;
	}

	static public String replaceCareful(String text, String find, String replace) {
		String[] split = text.split(find, -1);
		StringBuffer sb = new StringBuffer(); // FIXME calculate size
		for(int i=0;i<split.length;i++) {
			sb.append(split[i]);
			if(i < split.length - 1)
				sb.append(replace);
		}
		return sb.toString();
	}
	
	public HTMLNode getHTMLText() {
		HTMLNode alertNode = new HTMLNode("div");

		int disconnected = peers - conns;
		if (peers == 0) {
			if(n.isTestnetEnabled())
				alertNode.addChild("#", l10n("noPeersTestnet"));
			else
				alertNode.addChild("#", l10n("noPeersDarknet")); 
		} else if (conns == 0) {
			alertNode.addChild("#", l10n("noConns"));
		} else if (conns == 1) {
			alertNode.addChild("#", l10n("oneConn"));
		} else if (conns == 2) {
			alertNode.addChild("#", l10n("twoConns"));
		} else if (neverConn > MAX_NEVER_CONNECTED_PEER_ALERT_THRESHOLD) {
			L10n.addL10nSubstitution(alertNode, "tooManyNeverConnectedWithLink",
					new String[] { "link", "/link", "count" },
					new String[] { "<a href=\"/friends/myref.fref\">", "</a>", Integer.toString(neverConn) });
		} else if ((peers - conns) > MAX_DISCONN_PEER_ALERT_THRESHOLD) {
			alertNode.addChild("#", l10n("tooManyDisconnected", new String[] { "count", "max" }, new String[] { Integer.toString(disconnected), Integer.toString(MAX_DISCONN_PEER_ALERT_THRESHOLD)}));
		} else if (conns > MAX_CONN_ALERT_THRESHOLD) {
			alertNode.addChild("#", l10n("tooManyConns", new String[] { "count", "max" }, 
					new String[] { Integer.toString(conns), Integer.toString(MAX_CONN_ALERT_THRESHOLD)}));
		} else if (peers > MAX_PEER_ALERT_THRESHOLD) {
			alertNode.addChild("#", l10n("tooManyPeers", new String[] { "count", "max" },
					new String[] { Integer.toString(peers), Integer.toString(MAX_PEER_ALERT_THRESHOLD)}));
		} else if (n.bwlimitDelayAlertRelevant && (bwlimitDelayTime > NodeStats.MAX_BWLIMIT_DELAY_TIME_ALERT_THRESHOLD)) {
			alertNode.addChild("#", l10n("tooHighBwlimitDelayTime", new String[] { "delay", "max" },
					new String[] { Integer.toString(bwlimitDelayTime), Long.toString(NodeStats.MAX_BWLIMIT_DELAY_TIME_ALERT_THRESHOLD)}));
		} else if (n.nodeAveragePingAlertRelevant && (nodeAveragePingTime > NodeStats.MAX_NODE_AVERAGE_PING_TIME_ALERT_THRESHOLD)) {
			alertNode.addChild("#", l10n("tooHighPingTime", new String[] { "ping", "max" },
					new String[] { Integer.toString(nodeAveragePingTime), Long.toString(NodeStats.MAX_NODE_AVERAGE_PING_TIME_ALERT_THRESHOLD) }));
		} else if (oldestNeverConnectedPeerAge > MAX_OLDEST_NEVER_CONNECTED_PEER_AGE_ALERT_THRESHOLD) {
			alertNode.addChild("#", l10n("tooOldNeverConnectedPeers"));
		} else throw new IllegalArgumentException("not valid");

		return alertNode;
	}

	public short getPriorityClass() {
		if((peers == 0) ||
				(conns == 0) ||
				(neverConn > MAX_NEVER_CONNECTED_PEER_ALERT_THRESHOLD) ||
				((peers - conns) > MAX_DISCONN_PEER_ALERT_THRESHOLD) ||
				(conns > MAX_CONN_ALERT_THRESHOLD) ||
				(peers > MAX_PEER_ALERT_THRESHOLD) ||
				(n.bwlimitDelayAlertRelevant && (bwlimitDelayTime > NodeStats.MAX_BWLIMIT_DELAY_TIME_ALERT_THRESHOLD)) ||
				(n.nodeAveragePingAlertRelevant && (nodeAveragePingTime > NodeStats.MAX_NODE_AVERAGE_PING_TIME_ALERT_THRESHOLD)))
			return UserAlert.CRITICAL_ERROR;
		return UserAlert.ERROR;
	}

	public boolean isValid() {
		// only update here so we don't get odd behavior with it fluctuating
		bwlimitDelayTime = (int) n.getBwlimitDelayTime();
		nodeAveragePingTime = (int) n.getNodeAveragePingTime();
		oldestNeverConnectedPeerAge = (int) n.peers.getOldestNeverConnectedPeerAge();
		return ((peers == 0) ||
				(conns < 3) ||
				(neverConn > MAX_NEVER_CONNECTED_PEER_ALERT_THRESHOLD) ||
				((peers - conns) > MAX_DISCONN_PEER_ALERT_THRESHOLD) ||
				(conns > MAX_CONN_ALERT_THRESHOLD) ||
				(peers > MAX_PEER_ALERT_THRESHOLD) ||
				(n.bwlimitDelayAlertRelevant && (bwlimitDelayTime > NodeStats.MAX_BWLIMIT_DELAY_TIME_ALERT_THRESHOLD)) ||
				(n.nodeAveragePingAlertRelevant && (nodeAveragePingTime > NodeStats.MAX_NODE_AVERAGE_PING_TIME_ALERT_THRESHOLD)) ||
				(oldestNeverConnectedPeerAge > MAX_OLDEST_NEVER_CONNECTED_PEER_AGE_ALERT_THRESHOLD)) &&
				isValid;
	}
	
	public void isValid(boolean b){
		if(userCanDismiss()) isValid=b;
	}
	
	public String dismissButtonText(){
		return L10n.getString("UserAlert.hide");
	}
	
	public boolean shouldUnregisterOnDismiss() {
		return false;
	}
	
	public void onDismiss() {
		// do nothing on alert dismissal
	}
}
