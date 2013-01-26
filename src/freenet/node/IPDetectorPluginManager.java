package freenet.node;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import freenet.clients.http.ConnectivityToadlet;
import freenet.clients.http.ExternalLinkToadlet;
import freenet.io.AddressTracker;
import freenet.io.AddressTracker.Status;
import freenet.io.comm.FreenetInetAddress;
import freenet.io.comm.Peer;
import freenet.l10n.NodeL10n;
import freenet.node.useralerts.AbstractUserAlert;
import freenet.node.useralerts.ProxyUserAlert;
import freenet.node.useralerts.SimpleUserAlert;
import freenet.node.useralerts.UserAlert;
import freenet.pluginmanager.DetectedIP;
import freenet.pluginmanager.ForwardPort;
import freenet.pluginmanager.ForwardPortCallback;
import freenet.pluginmanager.ForwardPortStatus;
import freenet.pluginmanager.FredPlugin;
import freenet.pluginmanager.FredPluginIPDetector;
import freenet.pluginmanager.FredPluginPortForward;
import freenet.support.HTMLEncoder;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.transport.ip.IPUtil;

/**
 * Tracks all known IP address detection plugins, and runs them when appropriate.
 * Normally there would only be one, but sometimes there may be more than one.
 */
public class IPDetectorPluginManager implements ForwardPortCallback {
	
	public class PortForwardAlert extends AbstractUserAlert {

		private int[] portsNotForwarded;
		
		private short maxPriorityShown;
		private int maxPortsLength;
		
		private boolean valid;
		
		@Override
		public String anchor() {
			return "port-forward:"+super.hashCode();
		}

		@Override
		public String dismissButtonText() {
			return NodeL10n.getBase().getString("UserAlert.hide");
		}

		@Override
		public HTMLNode getHTMLText() {
			HTMLNode div = new HTMLNode("div");
			String url = ExternalLinkToadlet.escape(HTMLEncoder.encode(l10n("portForwardHelpURL")));
			boolean maybeForwarded = true;
			for(int portNotForwarded: portsNotForwarded) {
				if(portNotForwarded < 0) maybeForwarded = false;
			}
			String keySuffix = maybeForwarded ? "MaybeForwarded" : "NotForwarded";
			if(portsNotForwarded.length == 1) {
				NodeL10n.getBase().addL10nSubstitution(div, "IPDetectorPluginManager.forwardPort"+keySuffix, 
				        new String[] { "port", "link" },
				        new HTMLNode[] { HTMLNode.text(Math.abs(portsNotForwarded[0])), HTMLNode.link(url) });
			} else if(portsNotForwarded.length == 2) {
				NodeL10n.getBase().addL10nSubstitution(div, "IPDetectorPluginManager.forwardTwoPorts"+keySuffix, 
				        new String[] { "port1", "port2", "link", "connectivity" },
				        new HTMLNode[] { HTMLNode.text(Math.abs(portsNotForwarded[0])),
				                HTMLNode.text(Math.abs(portsNotForwarded[1])),
				                HTMLNode.link(url),
				                HTMLNode.link(ConnectivityToadlet.PATH) });
			} else {
				Logger.error(this, "Unknown number of ports to forward: "+portsNotForwarded.length);
			}
			if(innerGetPriorityClass() == UserAlert.ERROR) {
				div.addChild("#", " " + l10n("symmetricPS"));
			}
			return div;
		}

		@Override
		public short getPriorityClass() {
			return innerGetPriorityClass();
		}
		
		public short innerGetPriorityClass() {
			if(connectionType == DetectedIP.SYMMETRIC_NAT || connectionType == DetectedIP.SYMMETRIC_UDP_FIREWALL)
				// Only able to connect to directly connected / full cone nodes.
				return UserAlert.ERROR;
			if(portsNotForwarded != null) {
				for(int portNotForwarded: portsNotForwarded)
					if(portNotForwarded < 0) return UserAlert.ERROR;
			}
			return UserAlert.MINOR;
		}

		@Override
		public String getShortText() {
			String prefix = innerGetPriorityClass() == UserAlert.ERROR ?
					l10n("seriousConnectionProblems") : l10n("connectionProblems");
			prefix += " ";
			boolean maybeForwarded = true;
			for(int portNotForwarded: portsNotForwarded) {
				if(portNotForwarded < 0) maybeForwarded = false;
			}
			String keySuffix = maybeForwarded ? "MaybeForwarded" : "NotForwarded";
			if(portsNotForwarded.length == 1) {
				return prefix + l10n("forwardPortShort"+keySuffix, "port", Integer.toString(Math.abs(portsNotForwarded[0])));
			} else if(portsNotForwarded.length == 2) {
				return prefix + l10n("forwardTwoPortsShort"+keySuffix, new String[] { "port1", "port2" },
						new String[] { Integer.toString(Math.abs(portsNotForwarded[0])), Integer.toString(Math.abs(portsNotForwarded[1])) });
			} else {
				Logger.error(this, "Unknown number of ports to forward: "+portsNotForwarded.length);
				return "";
			}
		}

		@Override
		public String getText() {
			String url = l10n("portForwardHelpURL");
			boolean maybeForwarded = true;
			for(int portNotForwarded: portsNotForwarded) {
				if(portNotForwarded < 0) maybeForwarded = false;
			}
			String keySuffix = maybeForwarded ? "MaybeForwarded" : "NotForwarded";
			if(portsNotForwarded.length == 1) {
				return l10n("forwardPort"+keySuffix, new String[] { "port", "link", "/link" }, 
						new String[] { Integer.toString(Math.abs(portsNotForwarded[0])), "", " ("+url+")" });
			} else if(portsNotForwarded.length == 2) {
				return l10n("forwardTwoPorts"+keySuffix, new String[] { "port1", "port2", "link", "/link" },
						new String[] { Integer.toString(Math.abs(portsNotForwarded[0])), Integer.toString(Math.abs(portsNotForwarded[1])), "", " ("+url+")" });
			} else {
				Logger.error(this, "Unknown number of ports to forward: "+portsNotForwarded.length);
				return "";
			}
		}

		@Override
		public String getTitle() {
			return getShortText();
		}

		@Override
		public Object getUserIdentifier() {
			return IPDetectorPluginManager.this;
		}

		@Override
		public boolean isValid() {
			portsNotForwarded = getUDPPortsNotForwarded();
			if(portsNotForwarded.length > maxPortsLength) {
				valid = true;
				maxPortsLength = portsNotForwarded.length;
			}
			short prio = innerGetPriorityClass();
			if(prio < maxPriorityShown) {
				valid = true;
				maxPriorityShown = prio;
			}
			if(portsNotForwarded.length == 0) return false;
			return valid;
		}

		@Override
		public void isValid(boolean validity) {
			valid = validity;
		}

		@Override
		public void onDismiss() {
			valid = false;
		}

		@Override
		public boolean shouldUnregisterOnDismiss() {
			return false;
		}

		@Override
		public boolean userCanDismiss() {
			return true;
		}

		@Override
		public boolean isEventNotification() {
			return false;
		}
		
	}
	
	public class MyUserAlert extends AbstractUserAlert {

		final boolean suggestPortForward;
		private int[] portsNotForwarded;
		
		public MyUserAlert(String title, String text, boolean suggestPortForward, short code) {
			super(false, title, text, title, null, code, true, NodeL10n.getBase().getString("UserAlert.hide"), false, null);
			this.suggestPortForward = suggestPortForward;
			portsNotForwarded = new int[] { };
		}

		@Override
		public HTMLNode getHTMLText() {
			HTMLNode div = new HTMLNode("div");
			div.addChild("#", super.getText());
			if(suggestPortForward) {
				if(portsNotForwarded.length == 1) {
					NodeL10n.getBase().addL10nSubstitution(div,
					        "IPDetectorPluginManager.suggestForwardPortWithLink",
					        new String[] { "link", "port" },
					        new HTMLNode[] { HTMLNode.link(ExternalLinkToadlet.escape(
					                "http://wiki.freenetproject.org/FirewallAndRouterIssues")),
					                HTMLNode.text(portsNotForwarded[0])});
				} else {
					NodeL10n.getBase().addL10nSubstitution(div,
					        "IPDetectorPluginManager.suggestForwardTwoPortsWithLink",
					        new String[] { "link", "port1", "port2" },
					        new HTMLNode[] { HTMLNode.link(ExternalLinkToadlet.escape(
					                "http://wiki.freenetproject.org/FirewallAndRouterIssues")),
					                HTMLNode.text(portsNotForwarded[0]),
					                HTMLNode.text(portsNotForwarded[1]) });
				}
			}
			return div;
		}

		@Override
		public String getText() {
			if(!suggestPortForward) return super.getText();
			StringBuilder sb = new StringBuilder();
			sb.append(super.getText());
			if(portsNotForwarded.length == 1) {
				sb.append(l10n("suggestForwardPort", "port", Integer.toString(Math.abs(portsNotForwarded[0]))));
			} else if(portsNotForwarded.length >= 2) {
				sb.append(l10n("suggestForwardTwoPorts", new String[] { "port1", "port2" }, 
						new String[] { Integer.toString(Math.abs(portsNotForwarded[0])), Integer.toString(Math.abs(portsNotForwarded[1])) }));
				if(portsNotForwarded.length > 2)
					Logger.error(this, "Not able to tell user about more than 2 ports to forward! ("+portsNotForwarded.length+")");
			}
			
			return sb.toString();
		}

		@Override
		public void isValid(boolean validity) {
			valid = validity;
		}

		@Override
		public boolean isValid() {
			portsNotForwarded = getUDPPortsNotForwarded();
			return valid && (portsNotForwarded.length > 0);
		}
		
		@Override
		public void onDismiss() {
			valid = false;
		}
		
		@Override
		public boolean userCanDismiss() {
			return false;
		}

	}

	private static boolean logMINOR;
	private static boolean logDEBUG;
	static {
		Logger.registerClass(IPDetectorPluginManager.class);
	}
	private final NodeIPDetector detector;
	private final Node node;
	FredPluginIPDetector[] plugins;
	FredPluginPortForward[] portForwardPlugins;
	private final MyUserAlert noConnectionAlert;
	private final MyUserAlert symmetricAlert;
	private final MyUserAlert portRestrictedAlert;
	private final MyUserAlert restrictedAlert;
	private short connectionType;
	private ProxyUserAlert proxyAlert;
	private final PortForwardAlert portForwardAlert;
	private boolean started;
	
	IPDetectorPluginManager(Node node, NodeIPDetector detector) {
		plugins = new FredPluginIPDetector[0];
		portForwardPlugins = new FredPluginPortForward[0];
		this.node = node;
		this.detector = detector;
		noConnectionAlert = new MyUserAlert( l10n("noConnectivityTitle"), l10n("noConnectivity"), 
				true, UserAlert.ERROR);
		symmetricAlert = new MyUserAlert(l10n("symmetricTitle"), l10n("symmetric"), 
				true, UserAlert.ERROR);
		portRestrictedAlert = new MyUserAlert(l10n("portRestrictedTitle"), l10n("portRestricted"), 
				true, UserAlert.WARNING);
		restrictedAlert = new MyUserAlert(l10n("restrictedTitle"), l10n("restricted"), 
				false, UserAlert.MINOR);
		portForwardAlert = new PortForwardAlert();
	}

	/**
	 * Return all the ports that we have reason to believe are not forwarded. E.g. for the user-alert, which only
	 * shows if what we return is of nonzero length.
	 */
	public int[] getUDPPortsNotForwarded() {
		OpennetManager om = node.getOpennet();
		Status darknetStatus = (node.peers.anyDarknetPeers() ? node.darknetCrypto.getDetectedConnectivityStatus() : AddressTracker.Status.DONT_KNOW);
		Status opennetStatus = om == null ? Status.DONT_KNOW : om.crypto.getDetectedConnectivityStatus();
		if(om == null || opennetStatus.ordinal() >= AddressTracker.Status.DONT_KNOW.ordinal()) {
			if(darknetStatus.ordinal() >= AddressTracker.Status.DONT_KNOW.ordinal()) {
				return new int[] { };
			} else {
				return new int[] { (darknetStatus.ordinal() < AddressTracker.Status.MAYBE_NATED.ordinal() ? -1 : 1) * node.getDarknetPortNumber() };
			}
		} else {
			if(darknetStatus.ordinal() >= AddressTracker.Status.DONT_KNOW.ordinal()) {
				return new int[] { (opennetStatus.ordinal() < AddressTracker.Status.MAYBE_NATED.ordinal() ? -1 : 1 ) * om.crypto.portNumber };
			} else {
				return new int[] { ((darknetStatus.ordinal() < AddressTracker.Status.MAYBE_NATED.ordinal()) ? -1 : 1 ) * node.getDarknetPortNumber(), 
						(opennetStatus.ordinal() < AddressTracker.Status.MAYBE_NATED.ordinal() ? -1 : 1 ) * om.crypto.portNumber };
			}
		}
	}

	private String l10n(String key) {
		return NodeL10n.getBase().getString("IPDetectorPluginManager."+key);
	}

	public String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("IPDetectorPluginManager."+key, new String[] { pattern }, new String[] { value });
	}

	public String l10n(String key, String[] patterns, String[] values) {
		return NodeL10n.getBase().getString("IPDetectorPluginManager."+key, patterns, values);
	}

	/** Start the detector plugin manager. This includes running the plugin, if there
	 * is one, and if it is necessary to do so. */
	void start() {
		// Cannot be initialized until UserAlertManager has been created.
		proxyAlert = new ProxyUserAlert(node.clientCore.alerts, false);
		node.clientCore.alerts.register(portForwardAlert);
		started = true;
		tryMaybeRun();
	}
	
	/**
	 * Start the plugin detection, if necessary. Either way, schedule another attempt in
	 * 1 minute's time.
	 */
	private void tryMaybeRun() {
		try {
			maybeRun();
		} catch (Throwable t) {
			Logger.error(this, "Caught "+t, t);
		}
		node.getTicker().queueTimedJob(new Runnable() {
			@Override
			public void run() {
				freenet.support.Logger.OSThread.logPID(this);
				tryMaybeRun();
			}
		}, 60*1000);
	}

	/**
	 * Register a plugin.
	 */
	public void registerDetectorPlugin(FredPluginIPDetector d) {
		if(d == null) throw new NullPointerException();
		synchronized(this) {
			lastDetectAttemptEndedTime = -1;
			plugins = Arrays.copyOf(plugins, plugins.length+1);
			plugins[plugins.length-1] = d;
		}
		if(logMINOR) Logger.minor(this, "Registering a new plugin : " + d);
		maybeRun();
	}

	/**
	 * Remove a plugin.
	 */
	public void unregisterDetectorPlugin(FredPluginIPDetector d) {
		DetectorRunner runningDetector;
		synchronized(this) {
			int count = 0;
			for(FredPluginIPDetector plugin: plugins) {
				if(plugin == d) count++;
			}
			if(count == 0) return;
			FredPluginIPDetector[] newPlugins = new FredPluginIPDetector[plugins.length - count];
			int x = 0;
			for(FredPluginIPDetector plugin: plugins) {
				if(plugin != d) newPlugins[x++] = plugin;
			}
			plugins = newPlugins;
			// Will be removed when returns in the DetectorRunner
			runningDetector = runners.get(d);
		}
                if(runningDetector != null)
                    runningDetector.kill();
	}

	
	/* When should we run an IP address detection? This is for things like STUN, so
	 * there may conceivably be some exposure or risk, or limited resources, so not 
	 * all the time.
	 * 
	 * If we don't get a real IP address from a detection, we should not run another
	 * one for 5 minutes. This indicated that we were not on the internet *at all*.
	 * 
	 * If we have a directly detected IP, and:
	 * - We have no peers older than 30 minutes OR
	 * - We have successfully connected to two different peers with different real 
	 *   internet addresses to us since startup
	 *
	 * Then we should not run a detection. (However, we don't entirely exclude it 
	 * because we may be behind a firewall).
	 * 
	 * If we have no peers, and we haven't run a detection in the last 6 hours (don't
	 * save this time over startups), we should run a detection.
	 * 
	 * Otherwise, we have peers, and if we have run a detection in the last hour we 
	 * should not run another one.
	 * 
	 * If we have one or two connected peers, both of which report the same IP 
	 * address, and we have other nodes which have been connected recently, and this 
	 * state has persisted for 2 minutes, we should run a detection.
	 * (To protect against bogus IP address reports)
	 * 
	 * If we have no connected peers with real internet addresses, and this state has
	 * persisted for 2 minutes, and we have disconnected peers, then we should run a 
	 * detection. (every hour that we are down)
	 * (To detect new IP address)
	 */ 
	
	private HashMap<FredPluginIPDetector,DetectorRunner> runners = new HashMap<FredPluginIPDetector,DetectorRunner>();
	private HashSet<FredPluginIPDetector> failedRunners = new HashSet<FredPluginIPDetector>();
	private long lastDetectAttemptEndedTime;
	private long firstTimeUrgent;
	
	/**
	 * Do we need to run a plugin?
	 */
	public void maybeRun() {
		if(!started) return;
		if(logMINOR) Logger.minor(this, "Maybe running IP detection plugins", new Exception("debug"));
		PeerNode[] peers = node.getPeerNodes();
		PeerNode[] conns = node.getConnectedPeers();
		int peerCount = node.peers.countValidPeers();
		FreenetInetAddress[] nodeAddrs = detector.getPrimaryIPAddress(true);
		long now = System.currentTimeMillis();
		synchronized(this) {
			if(plugins.length == 0) {
				if(logMINOR) Logger.minor(this, "No IP detection plugins");
				detector.hasDetectedPM();
				return;
			}
			if(runners.size() == plugins.length) {
				if(logMINOR) Logger.minor(this, "Already running all IP detection plugins");
				return;
			}
			
			// If detect attempt failed to produce an IP in the last 5 minutes, don't
			// try again yet.
			if(failedRunners.size() == plugins.length) {
				if(now - lastDetectAttemptEndedTime < 5*60*1000) {
					if(logMINOR) Logger.minor(this, "Last detect failed less than 5 minutes ago");
					return;
				} else {
					if(logMINOR) Logger.minor(this, "Last detect failed, redetecting");
					startDetect();
					return;
				}
			}
			if(detector.hasDirectlyDetectedIP()) {
				
				if(!shouldDetectDespiteRealIP(now, conns, nodeAddrs)) return;
				
			}
			
			if(peerCount == 0) {
				
				if(shouldDetectNoPeers(now)) startDetect();
				
			} else {
				
				if(shouldDetectWithPeers(now, peers, conns, nodeAddrs)) startDetect();
				
			}
		}
		
	}

	/**
	 * Given that we have no peers, should we run the detection plugins?
	 * Algorithm: Run the detection once every 6 hours.
	 * @param now The time at the start of the calling method.
	 * @return True if we should run a detection.
	 */
	private boolean shouldDetectNoPeers(long now) {
		if(now - lastDetectAttemptEndedTime < 6*60*60*1000) {
			// No peers, only try every 6 hours.
			if(logMINOR) Logger.minor(this, "No peers but detected less than 6 hours ago");
			return false;
		} else {
			// Must try once!
			return true;
		}
	}

	/**
	 * Given that we have some peers, should we run the detection plugins?
	 * @param now The time at the beginning of the calling method.
	 * @param peers The node's peers.
	 * @param conns The node's connected peers.
	 * @return True if we should run a detection.
	 */
	private boolean shouldDetectWithPeers(long now, PeerNode[] peers, PeerNode[] conns, FreenetInetAddress[] nodeAddrs) {
		
		boolean detect = false;
		
		// If we have no connections, and several disconnected but enabled 
		// peers, then run a detection.
		
		int realConnections = 0;
		int realDisconnected = 0;
		int recentlyConnected = 0;
		
		if(logMINOR) Logger.minor(this, "Checking whether should detect with "+peers.length+" peers and "+conns.length+" conns, counting peers...");
		for(PeerNode p: peers) {
			if(p.isDisabled()) continue;
			// Don't count localhost, LAN addresses.
			Peer peer = p.getPeer();
			if(peer == null) continue;
			FreenetInetAddress a = peer.getFreenetAddress();
			if(a == null) continue; // Not much chance of connecting.
			InetAddress addr = a.getAddress(false);
			if(addr != null) {
				if(!IPUtil.isValidAddress(addr, false)) continue;
			}
			boolean skip = false;
			for(FreenetInetAddress nodeAddr: nodeAddrs) {
				if(a.equals(nodeAddr)) {
					skip = true;
					break;
				}
			}
			if(skip) continue;
			if(p.isConnected())
				realConnections++;
			else {
				realDisconnected++;
				if(now - p.lastReceivedPacketTime() < 5*60*1000)
					recentlyConnected++;
			}
		}
		
		// If we have no connections, and several disconnected nodes, we should do a
		// detection soon.
		if(logMINOR) Logger.minor(this, "Real connections: "+realConnections+" disconnected "+realDisconnected);
		if(realConnections == 0 && realDisconnected > 0) {
			if(firstTimeUrgent <= 0)
				firstTimeUrgent = now;
			
			if(detector.oldIPAddress != null && detector.oldIPAddress.isRealInternetAddress(false, false, false)) {
				if(logDEBUG) Logger.debug(this, "Detecting in 2 minutes as have oldIPAddress");
				// Allow 2 minutes to get incoming connections and therefore detect from them.
				// In the meantime, *hopefully* our oldIPAddress is valid.
				// If not, we'll find out in 2 minutes.
				if(now - firstTimeUrgent > 2*60*1000) {
					detect = true;
					firstTimeUrgent = now; // Reset now rather than on next round.
					if(logMINOR) Logger.minor(this, "Detecting now as 2 minutes are up (have oldIPAddress)");
				}
			} else {
				if(logMINOR) Logger.minor(this, "Detecting now (no oldIPAddress)");
				// Detect immediately
				detect = true;
			}
		} else if(realConnections == 0 && realDisconnected == 0) {
			return shouldDetectNoPeers(now);
		} else {
			if(logDEBUG) Logger.minor(this, "Not urgent; conns="+conns.length+", peers="+peers.length);
			firstTimeUrgent = 0;
		}
		
		// If we have no connections, and have lost several connections recently, we should 
		// do a detection soon, regardless of the 1 detection per hour throttle.
		if(realConnections == 0 && realDisconnected > 0 && recentlyConnected > 2) {
			if(now - lastDetectAttemptEndedTime > 6 * 60 * 1000) {
				return true;
			}
		}
		
		// If it appears to be an SNAT, do a detection at least once to verify that, and to
		// check whether our IP is bogus.
		if(detector.maybeSymmetric && lastDetectAttemptEndedTime <= 0)
			return true;
		
		if(detect) {
			if(now - lastDetectAttemptEndedTime < 60*60*1000) {
				// Only try every hour
				if(logMINOR) Logger.minor(this, "Only trying once per hour");
				return false;
			}
			
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Should we run the detection plugins despite having a directly detected IP address?
	 * @param now The time at the beginning of the calling method.
	 * @param peers The node's peers.
	 * @param nodeAddrs Our peers' addresses.
	 * @return True if we should run a detection.
	 */
	private boolean shouldDetectDespiteRealIP(long now, PeerNode[] peers, FreenetInetAddress[] nodeAddrs) {
		// We might still be firewalled?
		// First, check only once per day or startup
		if(now - lastDetectAttemptEndedTime < 12*60*60*1000) {
			if(logMINOR) Logger.minor(this, "Node has directly detected IP and we have checked less than 12 hours ago");
			return false;
		}
		
		if(logMINOR) Logger.minor(this, "Checking whether should detect despite real IP...");
		// Now, if we have two nodes with unique IPs which aren't ours
		// connected, we don't need to detect.
		HashSet<InetAddress> addressesConnected = null;
		boolean hasOldPeers = false;
		for(PeerNode p : peers) {
			if(p.isConnected() || (now - p.lastReceivedPacketTime() < 24*60*60*1000)) {
				// Has been connected in the last 24 hours.
				// Unique IP address?
				Peer peer = p.getPeer();
				if(peer != null){
					InetAddress addr = peer.getAddress(false);
					if(p.isConnected() && (addr != null) && IPUtil.isValidAddress(peer.getAddress(), false)) {
						// Connected node, on a real internet IP address.
						// Is it internal?
						boolean internal = false;
						for(FreenetInetAddress nodeAddr: nodeAddrs) {
							if(addr.equals(nodeAddr.getAddress(false))) {
								// Internal
								internal = true;
								break;
							}
						}
						if(!internal) {
							// Real IP address
							if(addressesConnected == null)
								addressesConnected = new HashSet<InetAddress>();
							addressesConnected.add(addr);
							if(addressesConnected.size() > 2) {
								// 3 connected addresses, lets assume we have connectivity.
								if(logMINOR) Logger.minor(this, "Node has directly detected IP and has connected to 3 real IPs");
								return false;
							}
						}
					}
				}
				long l = p.getPeerAddedTime();
				if((l <= 0) || (now - l > 30*60*1000)) {
					hasOldPeers = true;
				}
			}
		}
		if(!hasOldPeers) {
			// No peers older than 30 minutes
			if(logMINOR) Logger.minor(this, "Not detecting as less than 30 minutes old");
			return false;
		}
		return true;
	}

	private void startDetect() {
		if(logMINOR) Logger.minor(this, "Detecting...");
		synchronized(this) {
			failedRunners.clear();
			for(FredPluginIPDetector plugin: plugins) {
				if(runners.containsKey(plugin)) continue;
				DetectorRunner d = new DetectorRunner(plugin);
				runners.put(plugin, d);
				node.executor.execute(d, "Plugin detector runner for "+plugin.getClass());
			}
		}
	}

	public class DetectorRunner implements Runnable {
		
		final FredPluginIPDetector plugin;

		public DetectorRunner(FredPluginIPDetector detector) {
			plugin = detector;
		}

		public void kill() {
			node.pluginManager.killPlugin((FredPlugin)plugin, 0);
		}

		@Override
		public void run() {
			freenet.support.Logger.OSThread.logPID(this);
			try {
				realRun();
			} catch (OutOfMemoryError e) {
				OOMHandler.handleOOM(e);
			} catch (Throwable t) {
				Logger.error(this, "Caught "+t, t);
			}
		}
		
		public void realRun() {
			if(logMINOR) Logger.minor(this, "Running plugin detection");
			try {
				List<DetectedIP> v = new ArrayList<DetectedIP>();
				DetectedIP[] detected = null;
				try {
					detected = plugin.getAddress();
				} catch (Throwable t) {
					Logger.error(this, "Caught "+t, t);
				}
				if(detected != null) {
					for(DetectedIP d: detected)
						v.add(d);
				}
				synchronized(IPDetectorPluginManager.this) {
					lastDetectAttemptEndedTime = System.currentTimeMillis();
					boolean failed = false;
					if(v.isEmpty()) {
						if(logMINOR) Logger.minor(this, "No IPs found");
						failed = true;
					} else {
						failed = true;
						for(DetectedIP ip : v) {
							if(logMINOR) Logger.minor(this, "Detected IP: "+ip+" for "+plugin);
							if(!((ip.publicAddress == null) || !IPUtil.isValidAddress(ip.publicAddress, false))) {
								if(logMINOR) Logger.minor(this, "Address checked out");
								failed = false;
							}
						}
					}
					if(failed) {
						if(logMINOR) Logger.minor(this, "Failed");
						failedRunners.add(plugin);
						return;
					}
				}
				
				// Node does not know about individual interfaces, so just process the lot.
				
				// FIXME if we use the interfaces we should simply take the most popular conclusion for each one.
				
//				// Now tell the node
//				HashMap map = new LinkedHashMap();
//				for(int i=0;i<v.size();i++) {
//					DetectedIP d = (DetectedIP) v.get(i);
//					InetAddress addr = d.publicAddress;
//					if(!map.containsKey(addr)) {
//						map.put(addr, d);
//					} else {
//						DetectedIP oldD = (DetectedIP) map.get(addr);
//						if(!oldD.equals(d)) {
//							if(d.natType != DetectedIP.NOT_SUPPORTED) {
//								if(oldD.natType < d.natType) {
//									// Higher value = more restrictive.
//									// Assume the worst.
//									map.put(addr, d);
//								}
//							}
//						}
//					}
//				}
//				DetectedIP[] list = (DetectedIP[]) map.values().toArray(new DetectedIP[map.size()]);
				DetectedIP[] list = v.toArray(new DetectedIP[v.size()]);
				int countOpen = 0;
				int countFullCone = 0;
				int countRestricted = 0;
				int countPortRestricted = 0;
				int countSymmetric = 0;
				int countClosed = 0;
				for(DetectedIP d: list) {
					Logger.normal(this, "Detected IP: "+d.publicAddress+ " : type "+d.natType);
					System.out.println("Detected IP: "+d.publicAddress+ " : type "+d.natType);
					switch(d.natType) {
					case DetectedIP.FULL_CONE_NAT:
						countFullCone++;
						break;
					case DetectedIP.FULL_INTERNET:
						countOpen++;
						break;
					case DetectedIP.NO_UDP:
						countClosed++;
						break;
					case DetectedIP.NOT_SUPPORTED:
						// Ignore
						break;
					case DetectedIP.RESTRICTED_CONE_NAT:
						countRestricted++;
						break;
					case DetectedIP.PORT_RESTRICTED_NAT:
						countPortRestricted++;
						break;
					case DetectedIP.SYMMETRIC_NAT:
					case DetectedIP.SYMMETRIC_UDP_FIREWALL:
						countSymmetric++;
						break;
					}
				}
				
				if(countClosed > 0 && (countOpen + countFullCone + countRestricted + countPortRestricted + countSymmetric) == 0) {
					proxyAlert.setAlert(noConnectionAlert);
					proxyAlert.isValid(true);
					connectionType = DetectedIP.NO_UDP;
				} else if(countSymmetric > 0 && (countOpen + countFullCone + countRestricted + countPortRestricted == 0)) {
					proxyAlert.setAlert(symmetricAlert);
					proxyAlert.isValid(true);
					connectionType = DetectedIP.SYMMETRIC_NAT;
				} else if(countPortRestricted > 0 && (countOpen + countFullCone + countRestricted == 0)) {
					proxyAlert.setAlert(portRestrictedAlert);
					proxyAlert.isValid(true);
					connectionType = DetectedIP.PORT_RESTRICTED_NAT;
				} else if(countRestricted > 0 && (countOpen + countFullCone == 0)) {
					proxyAlert.setAlert(restrictedAlert);
					proxyAlert.isValid(true);
					connectionType = DetectedIP.RESTRICTED_CONE_NAT;
				} else if(countFullCone > 0 && countOpen == 0) {
					proxyAlert.isValid(false);
					connectionType = DetectedIP.FULL_CONE_NAT;
				} else if(countOpen > 0) {
					proxyAlert.isValid(false);
				}
				detector.processDetectedIPs(list);
				if(connectionType == DetectedIP.NO_UDP) {
					SimpleUserAlert toRegister = null;
					synchronized(this) {
						if(noConnectivityAlert == null)
							noConnectivityAlert = toRegister =
								new SimpleUserAlert(false, l10n("noConnectivityTitle"), l10n("noConnectivity"), l10n("noConnectivityShort"), UserAlert.ERROR);
					}
					if(toRegister != null)
						node.clientCore.alerts.register(toRegister);
				} else {
					UserAlert toKill;
					synchronized(this) {
						toKill = noConnectivityAlert;
						noConnectivityAlert = null;
					}
					if(toKill != null)
						node.clientCore.alerts.unregister(toKill);
				}
			} finally {
				boolean finished;
				synchronized(IPDetectorPluginManager.this) {
					runners.remove(plugin);
					finished = runners.isEmpty();
				}
				if(finished)
					detector.hasDetectedPM();
			}
		}

	}
	
	private SimpleUserAlert noConnectivityAlert;

	public synchronized boolean isEmpty() {
		return plugins.length == 0;
	}

	public void registerPortForwardPlugin(FredPluginPortForward forward) {
		if(forward == null) throw new NullPointerException();
		synchronized(this) {
			portForwardPlugins = Arrays.copyOf(portForwardPlugins, portForwardPlugins.length+1);
			portForwardPlugins[portForwardPlugins.length-1] = forward;
		}
		if(logMINOR) Logger.minor(this, "Registering a new port forward plugin : " + forward);
		forward.onChangePublicPorts(node.getPublicInterfacePorts(), this);
	}

	/**
	 * Remove a plugin.
	 */
	public void unregisterPortForwardPlugin(FredPluginPortForward forward) {
		synchronized(this) {
			int count = 0;
			for(FredPluginPortForward portForwardPlugin: portForwardPlugins) {
				if(portForwardPlugin == forward) count++;
			}
			if(count == 0) return;
			FredPluginPortForward[] newPlugins = new FredPluginPortForward[portForwardPlugins.length - count];
			int x = 0;
			for(FredPluginPortForward portForwardPlugin: portForwardPlugins) {
				if(portForwardPlugin != forward) newPlugins[x++] = portForwardPlugin;
			}
			portForwardPlugins = newPlugins;
		}
	}

	void notifyPortChange(final Set<ForwardPort> newPorts) {
		FredPluginPortForward[] plugins;
		synchronized(this) {
			plugins = portForwardPlugins;
		}
		for(final FredPluginPortForward plugin: plugins) {
			node.executor.execute(new Runnable() {

				@Override
				public void run() {
					try {
						plugin.onChangePublicPorts(newPorts, IPDetectorPluginManager.this);
					} catch (Throwable t) {
						Logger.error(this, "Changing public ports list on "+plugin+" threw: "+t, t);
					}
				}
				
			}, "Notify "+plugin+" of ports list change");
		}
	}

	@Override
	public void portForwardStatus(Map<ForwardPort, ForwardPortStatus> statuses) {
		Set<ForwardPort> currentPorts = node.getPublicInterfacePorts();
		for(ForwardPort p : currentPorts) {
			ForwardPortStatus status = statuses.get(p);
			if(status == null) continue;
			if(status.status == ForwardPortStatus.DEFINITE_SUCCESS) {
				Logger.normal(this, "Succeeded forwarding "+p.name+" port "+p.portNumber+" for "+p.protocol+" - port forward definitely succeeded "+status.reasonString);
			} else if(status.status == ForwardPortStatus.PROBABLE_SUCCESS) {
				Logger.normal(this, "Probably succeeded forwarding "+p.name+" port "+p.portNumber+" for "+p.protocol+" - port forward probably succeeded "+status.reasonString);
			} else if(status.status == ForwardPortStatus.MAYBE_SUCCESS) {
				Logger.normal(this, "Maybe succeeded forwarding "+p.name+" port "+p.portNumber+" for "+p.protocol+" - port forward may have succeeded but strongly recommend out of band verification "+status.reasonString);
			} else if(status.status == ForwardPortStatus.DEFINITE_FAILURE) {
				Logger.error(this, "Failed forwarding "+p.name+" port "+p.portNumber+" for "+p.protocol+" - port forward definitely failed "+status.reasonString);
			} else if(status.status == ForwardPortStatus.PROBABLE_FAILURE) {
				Logger.error(this, "Probably failed forwarding "+p.name+" port "+p.portNumber+" for "+p.protocol+" - port forward probably failed "+status.reasonString);
			}
			// Not much more we can do / want to do for now
			// FIXME use status.externalPort.
		}
		node.executor.execute(new Runnable() {
			@Override
			public void run() {
				maybeRun();
			}
		}, "Redetect IP after port forward changed");
	}

	public synchronized boolean hasDetectors() {
		return plugins.length > 0;
	}

	public void addConnectionTypeBox(HTMLNode contentNode) {
		if(node.clientCore == null) return;
		if(node.clientCore.alerts == null) return;
		if(proxyAlert == null) {
			Logger.error(this, "start() not called yet?", new Exception("debug"));
			return;
		}
		if(proxyAlert.isValid())
			contentNode.addChild(node.clientCore.alerts.renderAlert(proxyAlert));
	}

	public boolean hasJSTUN() {
		return node.pluginManager.isPluginLoadedOrLoadingOrWantLoad("JSTUN");
	}
	
}
