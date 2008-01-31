package freenet.node;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

import freenet.io.comm.FreenetInetAddress;
import freenet.io.comm.Peer;
import freenet.l10n.L10n;
import freenet.node.useralerts.AbstractUserAlert;
import freenet.node.useralerts.ProxyUserAlert;
import freenet.node.useralerts.UserAlert;
import freenet.pluginmanager.DetectedIP;
import freenet.pluginmanager.ForwardPort;
import freenet.pluginmanager.ForwardPortCallback;
import freenet.pluginmanager.ForwardPortStatus;
import freenet.pluginmanager.FredPlugin;
import freenet.pluginmanager.FredPluginIPDetector;
import freenet.pluginmanager.FredPluginPortForward;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.transport.ip.IPUtil;

/**
 * Tracks all known IP address detection plugins, and runs them when appropriate.
 * Normally there would only be one, but sometimes there may be more than one.
 */
public class IPDetectorPluginManager implements ForwardPortCallback {
	
	public class MyUserAlert extends AbstractUserAlert {

		final boolean suggestPortForward;
		private int[] portsNotForwarded;
		
		public MyUserAlert(String title, String text, boolean suggestPortForward, short code) {
			super(false, title, text, null, code, true, L10n.getString("UserAlert.hide"), false, null);
			this.suggestPortForward = suggestPortForward;
			portsNotForwarded = new int[] { };
		}

		public HTMLNode getHTMLText() {
			HTMLNode div = new HTMLNode("div");
			div.addChild("#", super.getText());
			if(suggestPortForward) {
				if(portsNotForwarded.length == 1) {
					L10n.addL10nSubstitution(div, "IPDetectorPluginManager.suggestForwardPortWithLink", new String[] { "link", "/link", "port" },
							new String[] { "<a href=\"/?_CHECKED_HTTP_=http://wiki.freenetproject.org/FirewallAndRouterIssues\">", "</a>", Integer.toString(portsNotForwarded[0]) });
				} else {
					L10n.addL10nSubstitution(div, "IPDetectorPluginManager.suggestForwardTwoPortsWithLink", new String[] { "link", "/link", "port1", "port2" },
							new String[] { "<a href=\"/?_CHECKED_HTTP_=http://wiki.freenetproject.org/FirewallAndRouterIssues\">", "</a>", Integer.toString(portsNotForwarded[0]), Integer.toString(portsNotForwarded[1]) });
				}
			}
			return div;
		}

		public String getText() {
			if(!suggestPortForward) return super.getText();
			StringBuffer sb = new StringBuffer();
			sb.append(super.getText());
			if(portsNotForwarded.length == 1) {
				sb.append(l10n("suggestForwardPort", "port", Integer.toString(portsNotForwarded[0])));
			} else if(portsNotForwarded.length >= 2) {
				sb.append(l10n("suggestForwardTwoPorts", new String[] { "port1", "port2" }, 
						new String[] { Integer.toString(portsNotForwarded[0]), Integer.toString(portsNotForwarded[1]) }));
				if(portsNotForwarded.length > 2)
					Logger.error(this, "Not able to tell user about more than 2 ports to forward! ("+portsNotForwarded.length+")");
			}
			
			return sb.toString();
		}

		public void isValid(boolean validity) {
			valid = validity;
		}

		public boolean isValid() {
			portsNotForwarded = getUDPPortsNotForwarded();
			return valid && (portsNotForwarded.length > 0);
		}
		
		public void onDismiss() {
			valid = false;
		}
		
		public boolean userCanDismiss() {
			return !suggestPortForward;
		}

	}

	static boolean logMINOR;
	private final NodeIPDetector detector;
	private final Node node;
	FredPluginIPDetector[] plugins;
	FredPluginPortForward[] portForwardPlugins;
	private final MyUserAlert noConnectionAlert;
	private final MyUserAlert symmetricAlert;
	private final MyUserAlert portRestrictedAlert;
	private final MyUserAlert restrictedAlert;
	private ProxyUserAlert proxyAlert;
	private boolean started;
	
	IPDetectorPluginManager(Node node, NodeIPDetector detector) {
		logMINOR = Logger.shouldLog(Logger.MINOR, getClass());
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
	}

	public int[] getUDPPortsNotForwarded() {
		OpennetManager om = node.getOpennet();
		if(om == null || om.crypto.definitelyPortForwarded()) {
			if(node.darknetDefinitelyPortForwarded()) {
				return new int[] { };
			} else {
				return new int[] { node.getDarknetPortNumber() };
			}
		} else {
			if(node.darknetDefinitelyPortForwarded()) {
				return new int[] { om.crypto.portNumber };
			} else {
				return new int[] { node.getDarknetPortNumber(), om.crypto.portNumber };
			}
		}
	}

	private String l10n(String key) {
		return L10n.getString("IPDetectorPluginManager."+key);
	}

	public String l10n(String key, String pattern, String value) {
		return L10n.getString("IPDetectorPluginManager."+key, new String[] { pattern }, new String[] { value });
	}

	public String l10n(String key, String[] patterns, String[] values) {
		return L10n.getString("IPDetectorPluginManager."+key, patterns, values);
	}

	/** Start the detector plugin manager. This includes running the plugin, if there
	 * is one, and if it is necessary to do so. */
	void start() {
		// Cannot be initialized until UserAlertManager has been created.
		proxyAlert = new ProxyUserAlert(node.clientCore.alerts);
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
			FredPluginIPDetector[] newPlugins = new FredPluginIPDetector[plugins.length+1];
			System.arraycopy(plugins, 0, newPlugins, 0, plugins.length);
			newPlugins[plugins.length] = d;
			plugins = newPlugins;
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
			for(int i=0;i<plugins.length;i++) {
				if(plugins[i] == d) count++;
			}
			if(count == 0) return;
			FredPluginIPDetector[] newPlugins = new FredPluginIPDetector[plugins.length - count];
			int x = 0;
			for(int i=0;i<plugins.length;i++) {
				if(plugins[i] != d) newPlugins[x++] = plugins[i];
			}
			plugins = newPlugins;
			// Will be removed when returns in the DetectorRunner
			runningDetector = (DetectorRunner) runners.get(d);
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
	
	private HashMap /*<FredIPDetectorPlugin,DetectorRunner>*/ runners = new HashMap();
	private boolean lastDetectAttemptFailed;
	private long lastDetectAttemptEndedTime;
	private long firstTimeMaybeFakePeers;
	private long firstTimeUrgent;
	
	/**
	 * Do we need to run a plugin?
	 */
	public void maybeRun() {
		if(!started) return;
		logMINOR = Logger.shouldLog(Logger.MINOR, getClass());
		if(logMINOR) Logger.minor(this, "Maybe running IP detection plugins", new Exception("debug"));
		PeerNode[] peers = node.getPeerNodes();
		PeerNode[] conns = node.getConnectedPeers();
		int peerCount = node.peers.countValidPeers();
		FreenetInetAddress[] nodeAddrs = detector.getPrimaryIPAddress();
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
			if(lastDetectAttemptFailed) {
				if(now - lastDetectAttemptEndedTime < 5*60*1000) {
					if(logMINOR) Logger.minor(this, "Last detect failed less than 5 minutes ago");
					return;
				} else {
					startDetect();
				}
			}
			if(detector.hasDirectlyDetectedIP()) {
				
				if(!shouldDetectDespiteRealIP(now, conns, nodeAddrs)) return;
				
			}
			
			if(peerCount == 0) {
				
				if(shouldDetectNoPeers(now)) startDetect();
				
			} else {
				
				if(shouldDetectWithPeers(now, peers, conns)) startDetect();
				
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
	private boolean shouldDetectWithPeers(long now, PeerNode[] peers, PeerNode[] conns) {
		
		boolean detect = false;
		
		// If we have no connections, and several disconnected but enabled 
		// peers, then run a detection.
		
		boolean maybeUrgent = false;
		
		if(conns.length == 0) {
			
			// No connections.
			for(int i=0;i<peers.length;i++) {
				PeerNode p = peers[i];
				if(!p.isDisabled()) {
					maybeUrgent = true;
					if(logMINOR) Logger.minor(this, "No connections, but have peers, may detect...");
					break;
				}
			}
		}
		
		if(detector.maybeSymmetric && lastDetectAttemptEndedTime <= 0) // If it appears to be an SNAT, do a detection at least once
			maybeUrgent = true;
		
		if(maybeUrgent) {
			if(firstTimeUrgent <= 0)
				firstTimeUrgent = now;
			
			if(now - firstTimeUrgent > 2*60*1000)
				detect = true;
			
			if(!(detector.oldIPAddress != null && detector.oldIPAddress.isRealInternetAddress(false, false)))
				detect = true; // else wait 2 minutes
			
		} else {
			if(logMINOR) Logger.minor(this, "Not urgent; conns="+conns.length+", peers="+peers.length);
			firstTimeUrgent = 0;
		}
		
		// Do the possibly-fake-IPs detection.
		// If we have one or two peers connected now, reporting real IPs, and 
		// if there is a locally detected address they are different to it, 
		// and other peers have been connected, then maybe we need to redetect
		// to make sure we're not being spoofed.
		
		boolean maybeFake = false;
	
		if(!detector.hasDirectlyDetectedIP()) {
			
			if((conns.length > 0) && (conns.length < 3)) {
				// No locally detected IP, only one or two connections.
				// Have we had more relatively recently?
				int count = 0;
				for(int i=0;i<peers.length;i++) {
					PeerNode p = peers[i];
					if((!p.isConnected()) && (now - p.lastReceivedPacketTime() < 5*60*1000)) {
						// Not connected now but has been within the past 5 minutes.
						count++;
					}
				}
				if(count > 2) {
					if(logMINOR) Logger.minor(this, "Recently connected peers count: "+count);
					maybeFake = true;
				}
			}
		}
		
		if(maybeFake) {
			if(logMINOR) Logger.minor(this, "Possible fake IPs being fed to us, may detect...");
			if(firstTimeMaybeFakePeers <= 0)
				firstTimeMaybeFakePeers = now;
			
			if((now - firstTimeMaybeFakePeers) > 2*60*1000) {
				// MaybeFake been true for 2 minutes.
				detect = true;
			}
		
		} else {
			if(logMINOR) Logger.minor(this, "Not fake");
			firstTimeMaybeFakePeers = 0;
		}
	
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
		
		// Now, if we have two nodes with unique IPs which aren't ours
		// connected, we don't need to detect.
		HashSet addressesConnected = null;
		boolean hasOldPeers = false;
		for(int i=0;i<peers.length;i++) {
			PeerNode p = peers[i];
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
						for(int j=0;j<nodeAddrs.length;j++) {
							if(addr.equals(nodeAddrs[j].getAddress())) {
								// Internal
								internal = true;
								break;
							}
						}
						if(!internal) {
							// Real IP address
							if(addressesConnected == null)
								addressesConnected = new HashSet();
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
			for(int i=0;i<plugins.length;i++) {
				FredPluginIPDetector plugin = plugins[i];
				if(runners.containsKey(plugin)) continue;
				DetectorRunner d = new DetectorRunner(plugins[i]);
				runners.put(plugin, d);
				node.executor.execute(d, "Plugin detector runner for "+plugins[i].getClass());
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
				Vector v = new Vector();
				DetectedIP[] detected = null;
				try {
					detected = plugin.getAddress();
				} catch (Throwable t) {
					Logger.error(this, "Caught "+t, t);
				}
				if(detected != null) {
					for(int j=0;j<detected.length;j++)
						v.add(detected[j]);
				}
				synchronized(IPDetectorPluginManager.this) {
					lastDetectAttemptEndedTime = System.currentTimeMillis();
					boolean failed = false;
					if(v.isEmpty()) {
						if(logMINOR) Logger.minor(this, "No IPs found");
						failed = true;
					} else {
						failed = true;
						for(int i=0;i<v.size();i++) {
							DetectedIP ip = (DetectedIP) v.get(i);
							if(!((ip.publicAddress == null) || !IPUtil.isValidAddress(ip.publicAddress, false))) {
								if(logMINOR) Logger.minor(this, "Address checked out");
								failed = false;
							}
						}
					}
					if(failed) {
						if(logMINOR) Logger.minor(this, "Failed");
						lastDetectAttemptFailed = true;
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
				DetectedIP[] list = (DetectedIP[]) v.toArray(new DetectedIP[v.size()]);
				int countOpen = 0;
				int countFullCone = 0;
				int countRestricted = 0;
				int countPortRestricted = 0;
				int countSymmetric = 0;
				int countClosed = 0;
				for(int i=0;i<list.length;i++) {
					Logger.normal(this, "Detected IP: "+list[i].publicAddress+ " : type "+list[i].natType);
					System.out.println("Detected IP: "+list[i].publicAddress+ " : type "+list[i].natType);
					switch(list[i].natType) {
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
				} else if(countSymmetric > 0 && (countOpen + countFullCone + countRestricted + countPortRestricted == 0)) {
					proxyAlert.setAlert(symmetricAlert);
					proxyAlert.isValid(true);
				} else if(countPortRestricted > 0 && (countOpen + countFullCone + countRestricted == 0)) {
					proxyAlert.setAlert(portRestrictedAlert);
					proxyAlert.isValid(true);
				} else if(countRestricted > 0 && (countOpen + countFullCone == 0)) {
					proxyAlert.setAlert(restrictedAlert);
					proxyAlert.isValid(true);
				} else if(countFullCone > 0 && countOpen == 0) {
					proxyAlert.isValid(false);
				} else if(countOpen > 0) {
					proxyAlert.isValid(false);
				}
				detector.processDetectedIPs(list);
			} finally {
				synchronized(IPDetectorPluginManager.this) {
					runners.remove(plugin);
				}
				detector.hasDetectedPM();
			}
		}

	}

	public boolean isEmpty() {
		return plugins.length == 0;
	}

	public void registerPortForwardPlugin(FredPluginPortForward forward) {
		if(forward == null) throw new NullPointerException();
		synchronized(this) {
			FredPluginPortForward[] newForwardPlugins = new FredPluginPortForward[portForwardPlugins.length+1];
			System.arraycopy(portForwardPlugins, 0, newForwardPlugins, 0, portForwardPlugins.length);
			newForwardPlugins[portForwardPlugins.length] = forward;
			portForwardPlugins = newForwardPlugins;
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
			for(int i=0;i<portForwardPlugins.length;i++) {
				if(portForwardPlugins[i] == forward) count++;
			}
			if(count == 0) return;
			FredPluginPortForward[] newPlugins = new FredPluginPortForward[portForwardPlugins.length - count];
			int x = 0;
			for(int i=0;i<portForwardPlugins.length;i++) {
				if(portForwardPlugins[i] != forward) newPlugins[x++] = portForwardPlugins[i];
			}
			portForwardPlugins = newPlugins;
		}
	}

	void notifyPortChange(Set newPorts) {
		FredPluginPortForward[] plugins;
		synchronized(this) {
			plugins = portForwardPlugins;
		}
		for(int i=0;i<plugins.length;i++)
			plugins[i].onChangePublicPorts(newPorts, this);
	}

	public void portForwardStatus(Map statuses) {
		Set currentPorts = node.getPublicInterfacePorts();
		Iterator i = currentPorts.iterator();
		while(i.hasNext()) {
			ForwardPort p = (ForwardPort) i.next();
			ForwardPortStatus status = (ForwardPortStatus) statuses.get(p);
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
			public void run() {
				maybeRun();
			}
		}, "Redetect IP after port forward changed");
	}

	public synchronized boolean hasDetectors() {
		return plugins.length > 0;
	}
	
}
