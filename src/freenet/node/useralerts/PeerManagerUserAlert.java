/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.useralerts;

import freenet.l10n.NodeL10n;
import freenet.node.NodeStats;
import freenet.node.PeerManager;
import freenet.node.updater.NodeUpdateManager;
import freenet.support.HTMLNode;

public class PeerManagerUserAlert extends AbstractUserAlert {

	final NodeStats n;
	final NodeUpdateManager nodeUpdater;
	// FIXME see comments in update().
	public int conns = 0;
	public int peers = 0;
	public int neverConn = 0;
	public int clockProblem = 0;
	public int connError = 0;
	public int disconnDarknetPeers = 0;
	int bwlimitDelayTime = 1;
	int nodeAveragePingTime = 1;
	long oldestNeverConnectedPeerAge = 0;
	private boolean bwlimitDelayAlertRelevant;
	private boolean nodeAveragePingAlertRelevant;
	public int darknetConns = 0;
	public int darknetPeers = 0;
	public int tooNewPeersDarknet = 0;
	public int tooNewPeersTotal = 0;
	public boolean isOpennetEnabled;
	public boolean darknetDefinitelyPortForwarded;
	public boolean opennetDefinitelyPortForwarded;
	public boolean opennetAssumeNAT;
	public boolean darknetAssumeNAT;
	private boolean isOutdated;
	
	/** How many connected peers we need to not get alert about not enough */
	public static final int MIN_CONN_ALERT_THRESHOLD = 3;
	
	/** How many connected peers we can have without getting alerted about too many */
	public static final int MAX_CONN_ALERT_THRESHOLD = 40;
	
	/** How many disconnected peers we can have without getting alerted about too many */
	public static final int MAX_DISCONN_PEER_ALERT_THRESHOLD = 50;
	
	/** How many never-connected peers can we have without getting alerted about too many */
	public static final int MAX_NEVER_CONNECTED_PEER_ALERT_THRESHOLD = 5;
	
	/** How many peers with clock problems can we have without getting alerted about too many */
	public static final int MIN_CLOCK_PROBLEM_PEER_ALERT_THRESHOLD = 5;
	
	/** How many peers with unknown connection errors can we have without getting alerted */
	public static final int MIN_CONN_ERROR_ALERT_THRESHOLD = 5;
	
	/** How many peers we can have without getting alerted about too many */
	public static final int MAX_PEER_ALERT_THRESHOLD = 100;
	
	/** How high can oldestNeverConnectedPeerAge be before we alert (in milliseconds)*/
	public static final long MAX_OLDEST_NEVER_CONNECTED_PEER_AGE_ALERT_THRESHOLD = ((long) 2)*7*24*60*60*1000;  // 2 weeks
	
	public PeerManagerUserAlert(NodeStats n, NodeUpdateManager nodeUpdater) {
		super(false, null, null, null, null, (short) 0, true, NodeL10n.getBase().getString("UserAlert.hide"), false, null);
		this.n = n;
		this.nodeUpdater = nodeUpdater;
	}
	
	@Override
	public String getTitle() {
		synchronized(this) {
			if(isOutdated)
				return l10n("outdatedUpdateTitle");
			if(!isOpennetEnabled) {
				if(peers == 0)
					return l10n("noPeersTitle");
				if(conns == 0)
					return l10n("noConnsTitle");
				if(conns < MIN_CONN_ALERT_THRESHOLD)
					return l10n("onlyFewConnsTitle", "count", Integer.toString(conns));
			}
			if(bwlimitDelayAlertRelevant && (bwlimitDelayTime > NodeStats.MAX_BWLIMIT_DELAY_TIME_ALERT_THRESHOLD))
				return l10n("tooHighBwlimitDelayTimeTitle");
			if(nodeAveragePingAlertRelevant && (nodeAveragePingTime > NodeStats.MAX_NODE_AVERAGE_PING_TIME_ALERT_THRESHOLD))
				return l10n("tooHighPingTimeTitle");
			if(clockProblem > MIN_CLOCK_PROBLEM_PEER_ALERT_THRESHOLD)
				return l10n("clockProblemTitle");
			if(neverConn > MAX_NEVER_CONNECTED_PEER_ALERT_THRESHOLD)
				return l10n("tooManyNeverConnectedTitle");
			if(connError > MIN_CONN_ERROR_ALERT_THRESHOLD)
				return l10n("connErrorTitle");
			if(disconnDarknetPeers > MAX_DISCONN_PEER_ALERT_THRESHOLD && !darknetDefinitelyPortForwarded && !darknetAssumeNAT)
				return l10n("tooManyDisconnectedTitle");
			if(conns > MAX_CONN_ALERT_THRESHOLD)
				return l10n("tooManyConnsTitle");
			if(peers > MAX_PEER_ALERT_THRESHOLD)
				return l10n("tooManyPeersTitle");
			if(oldestNeverConnectedPeerAge > MAX_OLDEST_NEVER_CONNECTED_PEER_AGE_ALERT_THRESHOLD)
				return l10n("tooOldNeverConnectedPeersTitle");
			else throw new IllegalArgumentException("Not valid");
		}
	}
	
	@Override
	public String getShortText() {
		return getTitle();
	}
	
	private String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("PeerManagerUserAlert."+key, pattern, value);
	}

	private String l10n(String key, String[] pattern, String[] value) {
		return NodeL10n.getBase().getString("PeerManagerUserAlert."+key, pattern, value);
	}

	private String l10n(String key) {
		return NodeL10n.getBase().getString("PeerManagerUserAlert."+key);
	}

	@Override
	public String getText() {
		String s;
		synchronized(this) {
			if(isOutdated)
				return l10n("outdatedUpdate");
			if(peers == 0 && !isOpennetEnabled) {
				return l10n("noPeersDarknet"); 
			} else if(conns < 3 && clockProblem > MIN_CLOCK_PROBLEM_PEER_ALERT_THRESHOLD) {
				s = l10n("clockProblem", "count", Integer.toString(clockProblem));
			} else if(conns < 3 && connError > MIN_CONN_ERROR_ALERT_THRESHOLD && !isOpennetEnabled) {
				s = l10n("connError", "count", Integer.toString(connError));
			} else if(conns == 0 && !isOpennetEnabled) {
				return l10n("noConns");
			} else if(conns == 1 && !isOpennetEnabled) {
				return l10n("oneConn");
			} else if(conns == 2 && !isOpennetEnabled) {
				return l10n("twoConns");
			} else if(bwlimitDelayAlertRelevant && (bwlimitDelayTime > NodeStats.MAX_BWLIMIT_DELAY_TIME_ALERT_THRESHOLD)) {
				s = l10n("tooHighBwlimitDelayTime", new String[] { "delay", "max" },
						new String[] { Integer.toString(bwlimitDelayTime), Long.toString(NodeStats.MAX_BWLIMIT_DELAY_TIME_ALERT_THRESHOLD)});
				// FIXME I'm not convinced about the next one!
			} else if(nodeAveragePingAlertRelevant && (nodeAveragePingTime > NodeStats.MAX_NODE_AVERAGE_PING_TIME_ALERT_THRESHOLD)) {
				s = l10n("tooHighPingTime", new String[] { "ping", "max" },
						new String[] { Integer.toString(nodeAveragePingTime), Long.toString(NodeStats.MAX_NODE_AVERAGE_PING_TIME_ALERT_THRESHOLD) });
			} else if(clockProblem > MIN_CLOCK_PROBLEM_PEER_ALERT_THRESHOLD) {
				s = l10n("clockProblem", "count", Integer.toString(clockProblem));
			} else if(neverConn > MAX_NEVER_CONNECTED_PEER_ALERT_THRESHOLD) {
				s = l10n("tooManyNeverConnected", "count", Integer.toString(neverConn));
			} else if(connError > MIN_CONN_ERROR_ALERT_THRESHOLD) {
				s = l10n("connError", "count", Integer.toString(connError));
			} else if(disconnDarknetPeers > MAX_DISCONN_PEER_ALERT_THRESHOLD && !darknetDefinitelyPortForwarded && !darknetAssumeNAT){
				s = l10n("tooManyDisconnected", new String[] { "count", "max" }, 
						new String[] { Integer.toString(disconnDarknetPeers), Integer.toString(MAX_DISCONN_PEER_ALERT_THRESHOLD)});
			} else if(conns > MAX_CONN_ALERT_THRESHOLD) {
				s = l10n("tooManyConns", new String[] { "count", "max" }, 
						new String[] { Integer.toString(conns), Integer.toString(MAX_CONN_ALERT_THRESHOLD)});
			} else if(peers > MAX_PEER_ALERT_THRESHOLD) {
				s = l10n("tooManyPeers", new String[] { "count", "max" },
						new String[] { Integer.toString(peers), Integer.toString(MAX_PEER_ALERT_THRESHOLD)});
			} else if(oldestNeverConnectedPeerAge > MAX_OLDEST_NEVER_CONNECTED_PEER_AGE_ALERT_THRESHOLD) {
				return l10n("tooOldNeverConnectedPeers");
			} else throw new IllegalArgumentException("Not valid");
			return s;
		}
	}

	static public String replace(String text, String find, String replace) {
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
		StringBuilder sb = new StringBuilder(text.length() + (split.length-1)*(replace.length() - find.length()));
		for(int i=0;i<split.length;i++) {
			sb.append(split[i]);
			if(i < split.length - 1)
				sb.append(replace);
		}
		return sb.toString();
	}
	
	@Override
	public HTMLNode getHTMLText() {
		HTMLNode alertNode = new HTMLNode("div");
		
		synchronized(this) {
			if(isOutdated)
				// Arguably we should provide a button to turn on auto-update,
				// but very few users will turn off auto-update completely.
				// This is useful to not lose those who do however. 
				alertNode.addChild("#", l10n("outdatedUpdate"));
			else if (peers == 0 && !isOpennetEnabled) {
				alertNode.addChild("#", l10n("noPeersDarknet")); 
			} else if(conns < 3 && clockProblem > MIN_CLOCK_PROBLEM_PEER_ALERT_THRESHOLD) {
				alertNode.addChild("#", l10n("clockProblem", "count", Integer.toString(clockProblem)));
			} else if(conns < 3 && connError > MIN_CONN_ERROR_ALERT_THRESHOLD) {
				alertNode.addChild("#", l10n("connError", "count", Integer.toString(connError)));
			} else if (conns == 0 && !isOpennetEnabled) {
				alertNode.addChild("#", l10n("noConns"));
			} else if (conns == 1 && !isOpennetEnabled) {
				alertNode.addChild("#", l10n("oneConn"));
			} else if (conns == 2 && !isOpennetEnabled) {
				alertNode.addChild("#", l10n("twoConns"));
			} else if (bwlimitDelayAlertRelevant && (bwlimitDelayTime > NodeStats.MAX_BWLIMIT_DELAY_TIME_ALERT_THRESHOLD)) {
				alertNode.addChild("#", l10n("tooHighBwlimitDelayTime", new String[] { "delay", "max" },
						new String[] { Integer.toString(bwlimitDelayTime), Long.toString(NodeStats.MAX_BWLIMIT_DELAY_TIME_ALERT_THRESHOLD)}));
			} else if (nodeAveragePingAlertRelevant && (nodeAveragePingTime > NodeStats.MAX_NODE_AVERAGE_PING_TIME_ALERT_THRESHOLD)) {
				alertNode.addChild("#", l10n("tooHighPingTime", new String[] { "ping", "max" },
						new String[] { Integer.toString(nodeAveragePingTime), Long.toString(NodeStats.MAX_NODE_AVERAGE_PING_TIME_ALERT_THRESHOLD) }));
			} else if (clockProblem > MIN_CLOCK_PROBLEM_PEER_ALERT_THRESHOLD) {
				alertNode.addChild("#", l10n("clockProblem", "count", Integer.toString(clockProblem)));
			} else if (neverConn > MAX_NEVER_CONNECTED_PEER_ALERT_THRESHOLD) {
				NodeL10n.getBase().addL10nSubstitution(alertNode, "PeerManagerUserAlert.tooManyNeverConnectedWithLink",
						new String[] { "link", "count" },
						new HTMLNode[] { HTMLNode.link("/friends/myref.fref"), HTMLNode.text(neverConn) });
			} else if(connError > MIN_CONN_ERROR_ALERT_THRESHOLD) {
				alertNode.addChild("#", l10n("connError", "count", Integer.toString(connError)));
			} else if (disconnDarknetPeers > MAX_DISCONN_PEER_ALERT_THRESHOLD && !darknetDefinitelyPortForwarded && !darknetAssumeNAT) {
				alertNode.addChild("#", l10n("tooManyDisconnected", new String[] { "count", "max" }, new String[] { Integer.toString(disconnDarknetPeers), Integer.toString(MAX_DISCONN_PEER_ALERT_THRESHOLD)}));
			} else if (conns > MAX_CONN_ALERT_THRESHOLD) {
				alertNode.addChild("#", l10n("tooManyConns", new String[] { "count", "max" }, 
						new String[] { Integer.toString(conns), Integer.toString(MAX_CONN_ALERT_THRESHOLD)}));
			} else if (peers > MAX_PEER_ALERT_THRESHOLD) {
				alertNode.addChild("#", l10n("tooManyPeers", new String[] { "count", "max" },
						new String[] { Integer.toString(peers), Integer.toString(MAX_PEER_ALERT_THRESHOLD)}));
			} else if (oldestNeverConnectedPeerAge > MAX_OLDEST_NEVER_CONNECTED_PEER_AGE_ALERT_THRESHOLD) {
				alertNode.addChild("#", l10n("tooOldNeverConnectedPeers"));
			} else throw new IllegalArgumentException("not valid");
		}
		return alertNode;
	}

	private boolean calculateIsOutdated() {
		// Do not show the message if updater is enabled.
		if(nodeUpdater.isEnabled()) return false;
		synchronized(this) {
			if(tooNewPeersDarknet >= PeerManager.OUTDATED_MIN_TOO_NEW_DARKNET)
				return true;
			return conns < PeerManager.OUTDATED_MAX_CONNS && 
				tooNewPeersTotal >= PeerManager.OUTDATED_MIN_TOO_NEW_TOTAL;
		}
	}

	@Override
	public short getPriorityClass() {
		synchronized(this) {
			if(isOutdated) {
				if(conns == 0)
					return UserAlert.CRITICAL_ERROR;
				else
					return UserAlert.ERROR;
			}
			if(peers == 0 && !isOpennetEnabled)
				return UserAlert.CRITICAL_ERROR;
			if(conns == 0 && !isOpennetEnabled)
				return UserAlert.ERROR;
			if(conns < 3 && clockProblem > MIN_CLOCK_PROBLEM_PEER_ALERT_THRESHOLD)
				return ERROR;
			if(conns < 3 && connError > MIN_CONN_ERROR_ALERT_THRESHOLD)
				return ERROR;
			if(conns < 3 && !isOpennetEnabled)
				return ERROR;
			if(bwlimitDelayAlertRelevant && (bwlimitDelayTime > NodeStats.MAX_BWLIMIT_DELAY_TIME_ALERT_THRESHOLD))
				return ERROR;
			if(nodeAveragePingAlertRelevant && (nodeAveragePingTime > NodeStats.MAX_NODE_AVERAGE_PING_TIME_ALERT_THRESHOLD))
				return ERROR;
			if(clockProblem > MIN_CLOCK_PROBLEM_PEER_ALERT_THRESHOLD)
				return ERROR;
			if(neverConn > MAX_NEVER_CONNECTED_PEER_ALERT_THRESHOLD)
				return WARNING;
			if(connError > MIN_CONN_ERROR_ALERT_THRESHOLD)
				return WARNING;
			if(disconnDarknetPeers > MAX_DISCONN_PEER_ALERT_THRESHOLD && !darknetDefinitelyPortForwarded && !darknetAssumeNAT)
				return WARNING;
			if(conns > MAX_CONN_ALERT_THRESHOLD)
				return WARNING;
			if(peers > MAX_PEER_ALERT_THRESHOLD)
				return WARNING;
			if(oldestNeverConnectedPeerAge > MAX_OLDEST_NEVER_CONNECTED_PEER_AGE_ALERT_THRESHOLD)
				return WARNING;
			return ERROR;
		}
	}

	@Override
	public boolean isValid() {
		// only update here so we don't get odd behavior with it fluctuating
		update();
		boolean ret;
		synchronized(this) {
			ret = ((peers == 0 && !isOpennetEnabled) ||
				(conns < 3 && !isOpennetEnabled) ||
				(neverConn > MAX_NEVER_CONNECTED_PEER_ALERT_THRESHOLD) ||
				(disconnDarknetPeers > MAX_DISCONN_PEER_ALERT_THRESHOLD && !darknetDefinitelyPortForwarded && !darknetAssumeNAT) ||
				(conns > MAX_CONN_ALERT_THRESHOLD) ||
				(peers > MAX_PEER_ALERT_THRESHOLD) ||
				(clockProblem > MIN_CLOCK_PROBLEM_PEER_ALERT_THRESHOLD) ||
				(connError > MIN_CONN_ERROR_ALERT_THRESHOLD) ||
				(bwlimitDelayAlertRelevant && (bwlimitDelayTime > NodeStats.MAX_BWLIMIT_DELAY_TIME_ALERT_THRESHOLD)) ||
				(nodeAveragePingAlertRelevant && (nodeAveragePingTime > NodeStats.MAX_NODE_AVERAGE_PING_TIME_ALERT_THRESHOLD)) ||
				(oldestNeverConnectedPeerAge > MAX_OLDEST_NEVER_CONNECTED_PEER_AGE_ALERT_THRESHOLD));
		}
		if(!ret)
			ret = isOutdated;
		return ret;
	}

	private void update() {
		bwlimitDelayTime = (int) n.getBwlimitDelayTime();
		nodeAveragePingTime = (int) n.getNodeAveragePingTime();
		oldestNeverConnectedPeerAge = (int) n.peers.getOldestNeverConnectedDarknetPeerAge();
		bwlimitDelayAlertRelevant = n.bwlimitDelayAlertRelevant;
		nodeAveragePingAlertRelevant = n.nodeAveragePingAlertRelevant;
		isOutdated = calculateIsOutdated();
		// FIXME move PeerManager.updatePMUserAlert here.
		// FIXME then make this subscribe to PeerManager's listener thingy.
	}
	
}
