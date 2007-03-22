/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.useralerts;

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
			return "No peers found";
		if(conns == 0)
			return "No open connections";
		if(conns < MIN_CONN_ALERT_THRESHOLD) {
			String suff = "";
			if (conns > 1) suff = "s";
			return "Only "+conns+" open connection"+suff;
		}
		if(neverConn > MAX_NEVER_CONNECTED_PEER_ALERT_THRESHOLD)
			return "Many peers have not connected once yet";
		if((peers - conns) > MAX_DISCONN_PEER_ALERT_THRESHOLD)
			return "Too many disconnected peers";
		if(conns > MAX_CONN_ALERT_THRESHOLD)
			return "Too many open connections";
		if(peers > MAX_PEER_ALERT_THRESHOLD)
			return "Too many peers";
		if(n.bwlimitDelayAlertRelevant && (bwlimitDelayTime > NodeStats.MAX_BWLIMIT_DELAY_TIME_ALERT_THRESHOLD))
			return "bwlimitDelayTime too high";
		if(n.nodeAveragePingAlertRelevant && (nodeAveragePingTime > NodeStats.MAX_NODE_AVERAGE_PING_TIME_ALERT_THRESHOLD))
			return "nodeAveragePingTime too high";
		if(oldestNeverConnectedPeerAge > MAX_OLDEST_NEVER_CONNECTED_PEER_AGE_ALERT_THRESHOLD)
			return "Never connected peer(s) too old";
		else throw new IllegalArgumentException("Not valid");
	}
	
	static final String NO_PEERS_START = 
		"This node has no peers to connect to, therefore it will not " +
		"be able to function normally. Ideally you should connect to peers run by people you know " +
		"(if you are paranoid, then people you trust; if not, then at least people you've talked to). " +
		"You need at least 3 connected peers at all times, and ideally 5-10.";
	static final String NO_PEERS_LOG_ONTO_IRC = 
		" log on to irc.freenode.net channel #freenet-refs and ask around for somebody to connect to";
	static final String NO_PEERS_TESTNET = NO_PEERS_START +
		", but since this is a testnet node, we suggest that you " + NO_PEERS_LOG_ONTO_IRC + '.';
	static final String NO_PEERS_DARKNET = NO_PEERS_START +
		". You could " + NO_PEERS_LOG_ONTO_IRC + ", but remember that you are vulnerable to " +
		"those you are directly connected to. (This is especially true in this early alpha of Freenet 0.7...)\n" +
		"BE SURE THAT THE OTHER PERSON HAS ADDED YOUR REFERENCE, TOO, AS ONE-WAY CONNECTIONS WON'T WORK!";
	
	static final String NO_CONNS = 
		"This node has not been able to connect to any other nodes so far; it will not be able to function normally. " +
		"Hopefully some of your peers will connect soon; if not, try to get some more peers. You need at least 3 peers at any time, and should aim for 5-10.";
	
	static final String ONE_CONN = 
		"This node only has one connection. Performance will be impaired, and you have no anonymity nor even plausible deniability if that one person is malicious. " +
		"Your node is attached to the network like a \u201cleaf\u201d and does not contribute to the network's health. " +
		"Try to get at least 3 (ideally more like 5-10) connected peers at any given time.";
	
	static final String TWO_CONNS =
		"This node has only two connections. Performance and security will not be very good, and your node is not doing any routing for other nodes. " +
		"Your node is embedded like a 'chain' in the network and does not contribute (much) to the network's health. " +
		"Try to get at least 3 (ideally more like 5-10) connected peers at any given time.";
	
	static final String NEVER_CONN_START = 
		"Many of this node's peers have never connected even once: {NEVER_CONN}. You should not add peers unless you know that they have also added ";
	static final String NEVER_CONN_END = ". Otherwise they will not connect. " +
		"Also please note that adding large numbers of connections automatically is discouraged as it does not produce a small-world network, and therefore hurts routing.";
	static final String NEVER_CONN_MIDDLE_TEXT = "your reference";
	static final HTMLNode NEVER_CONN_MIDDLE_NODE() {
		return new HTMLNode("a", "href", "/darknet/myref.fref", "your reference");
	}
	
	static final String NEVER_CONN_TEXT =
		NEVER_CONN_START + NEVER_CONN_MIDDLE_TEXT + NEVER_CONN_END;
//	static final HTMLNode NEVER_CONN_MIDDLE_NODE =
//		new HTMLNode()
//		"<a href=\"/darknet/myref.txt\">your reference</a>
	
	static final String DISCONNECTED =
		"This node has too many disconnected peers ({DISCONNECTED} > "+MAX_DISCONN_PEER_ALERT_THRESHOLD+
		"). This will have a slight impact on your performance as disconnected peers also consume a small amount of bandwidth and CPU. Consider \"cleaning up\" your peer list. " +
		"Note that ideally you should connect to nodes run by people you know. Even if not, adding lots of nodes automatically is bad as it does not produce an optimal topology.";
	
	static final String TOO_MANY_CONNECTIONS =
		"This node has too many connections ({CONNS} > "+MAX_CONN_ALERT_THRESHOLD+"). Adding large numbers of nodes automatically does not produce a small-world topology, hurts routing, and risks producing single points of failure.";
	
	static final String TOO_MANY_PEERS =
		"This node has too many peers ({PEERS} > "+MAX_PEER_ALERT_THRESHOLD+"). We do not recommend running ubernodes with automated addition of peers; this does not produce a small world network topology." +
		"This will also marginally impact your performance as all peers (connected or not) consume a small amount of bandwidth and CPU. Consider \"cleaning up\" your peer list.";
	
	static final String TOO_HIGH_BWLIMITDELAYTIME =
		"This node has to wait too long for available bandwidth ({BWLIMIT_DELAY_TIME} > "+NodeStats.MAX_BWLIMIT_DELAY_TIME_ALERT_THRESHOLD+").  Increase your output bandwidth limit and/or remove some peers to improve the situation.";
	
	static final String TOO_HIGH_PING =
		"This node is having trouble talking with its peers quickly enough ({PING_TIME} > "+
		NodeStats.MAX_NODE_AVERAGE_PING_TIME_ALERT_THRESHOLD+").  Increase your output bandwidth limit and/or remove some peers to improve the situation.";

	static final String NEVER_CONNECTED_TWO_WEEKS =
		"One or more of your node's peers have never connected in the two weeks since they were added.  Consider removing them since they are marginally affecting performance.";
	
	public String getText() {
		String s;
		int disconnected = peers - conns;
		if(peers == 0) {
			if(n.isTestnetEnabled())
				return NO_PEERS_TESTNET;
			else
				return NO_PEERS_DARKNET; 
		} else if(conns == 0) {
			return NO_CONNS;
		} else if(conns == 1) {
			return ONE_CONN;
		} else if(conns == 2) {
			return TWO_CONNS;
		} else if(neverConn > MAX_NEVER_CONNECTED_PEER_ALERT_THRESHOLD) {
			s = replace(NEVER_CONN_TEXT, "\\{NEVER_CONN\\}", Integer.toString(neverConn));
		} else if((peers - conns) > MAX_DISCONN_PEER_ALERT_THRESHOLD){
			s = replace(DISCONNECTED, "\\{DISCONNECTED\\}", Integer.toString(disconnected));
		} else if(conns > MAX_CONN_ALERT_THRESHOLD) {
			s = replace(TOO_MANY_CONNECTIONS, "\\{CONNS\\}", Integer.toString(conns));
		} else if(peers > MAX_PEER_ALERT_THRESHOLD) {
			s = replace(TOO_MANY_PEERS, "\\{PEERS\\}", Integer.toString(peers));
		} else if(n.bwlimitDelayAlertRelevant && (bwlimitDelayTime > NodeStats.MAX_BWLIMIT_DELAY_TIME_ALERT_THRESHOLD)) {
			s = replace(TOO_HIGH_BWLIMITDELAYTIME, "\\{BWLIMIT_DELAY_TIME\\}", Integer.toString(bwlimitDelayTime));
			
			// FIXME I'm not convinced about the next one!
		} else if(n.nodeAveragePingAlertRelevant && (nodeAveragePingTime > NodeStats.MAX_NODE_AVERAGE_PING_TIME_ALERT_THRESHOLD)) {
			s = replace(TOO_HIGH_PING, "\\{PING_TIME\\}", Integer.toString(nodeAveragePingTime));
		} else if(oldestNeverConnectedPeerAge > MAX_OLDEST_NEVER_CONNECTED_PEER_AGE_ALERT_THRESHOLD) {
			s = NEVER_CONNECTED_TWO_WEEKS;
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
				alertNode.addChild("#", NO_PEERS_TESTNET);
			else
				alertNode.addChild("#", NO_PEERS_DARKNET); 
		} else if (conns == 0) {
			alertNode.addChild("#", NO_CONNS);
		} else if (conns == 1) {
			alertNode.addChild("#", ONE_CONN);
		} else if (conns == 2) {
			alertNode.addChild("#", TWO_CONNS);
		} else if (neverConn > MAX_NEVER_CONNECTED_PEER_ALERT_THRESHOLD) {
			alertNode.addChild("#", replace(NEVER_CONN_START, "\\{NEVER_CONN\\}", Integer.toString(neverConn)));
			alertNode.addChild(NEVER_CONN_MIDDLE_NODE());
			alertNode.addChild("#", replace(NEVER_CONN_END, "\\{NEVER_CONN\\}", Integer.toString(neverConn)));
		} else if ((peers - conns) > MAX_DISCONN_PEER_ALERT_THRESHOLD) {
			alertNode.addChild("#", replace(DISCONNECTED, "\\{DISCONNECTED\\}", Integer.toString(disconnected)));
		} else if (conns > MAX_CONN_ALERT_THRESHOLD) {
			alertNode.addChild("#", replace(TOO_MANY_CONNECTIONS, "\\{CONNS\\}", Integer.toString(conns)));
		} else if (peers > MAX_PEER_ALERT_THRESHOLD) {
			alertNode.addChild("#", replace(TOO_MANY_PEERS, "\\{PEERS\\}", Integer.toString(peers)));
		} else if (n.bwlimitDelayAlertRelevant && (bwlimitDelayTime > NodeStats.MAX_BWLIMIT_DELAY_TIME_ALERT_THRESHOLD)) {
			alertNode.addChild("#", replace(TOO_HIGH_BWLIMITDELAYTIME, "\\{BWLIMIT_DELAY_TIME\\}", Integer.toString(bwlimitDelayTime)));
		} else if (n.nodeAveragePingAlertRelevant && (nodeAveragePingTime > NodeStats.MAX_NODE_AVERAGE_PING_TIME_ALERT_THRESHOLD)) {
			alertNode.addChild("#", replace(TOO_HIGH_PING, "\\{PING_TIME\\}", Integer.toString(nodeAveragePingTime)));
		} else if (oldestNeverConnectedPeerAge > MAX_OLDEST_NEVER_CONNECTED_PEER_AGE_ALERT_THRESHOLD) {
			alertNode.addChild("#", NEVER_CONNECTED_TWO_WEEKS);
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
		return "Hide";
	}
	
	public boolean shouldUnregisterOnDismiss() {
		return false;
	}
	
	public void onDismiss() {
		// do nothing on alert dismissal
	}
}
