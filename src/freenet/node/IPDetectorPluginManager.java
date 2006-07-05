package freenet.node;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Vector;

import freenet.io.comm.Peer;
import freenet.pluginmanager.DetectedIP;
import freenet.pluginmanager.FredPluginIPDetector;
import freenet.support.Logger;
import freenet.transport.IPUtil;

/**
 * Tracks all known IP address detection plugins, and runs them when appropriate.
 * Normally there would only be one, but sometimes there may be more than one.
 */
public class IPDetectorPluginManager {
	
	private final Node node;
	FredPluginIPDetector[] plugins;
	
	IPDetectorPluginManager(Node node) {
		plugins = new FredPluginIPDetector[0];
		this.node = node;
	}

	void start() {
		try {
			maybeRun();
		} catch (Throwable t) {
			Logger.error(this, "Caught "+t, t);
		}
		node.ps.queueTimedJob(new Runnable() {
			public void run() {
				start();
			}
		}, 60*1000);
		
	}
	
	/**
	 * Register a plugin.
	 */
	public void register(FredPluginIPDetector d) {
		if(d == null) throw new NullPointerException();
		synchronized(this) {
			FredPluginIPDetector[] newPlugins = new FredPluginIPDetector[plugins.length+1];
			System.arraycopy(plugins, 0, newPlugins, 0, plugins.length);
			newPlugins[plugins.length] = d;
			plugins = newPlugins;
		}
		maybeRun();
	}

	/**
	 * Remove a plugin.
	 */
	public void remove(FredPluginIPDetector d) {
		synchronized(this) {
			int count = 0;
			for(int i=0;i<plugins.length;i++) {
				if(plugins[i] == d) count++;
			}
			if(count == 0) return;
			FredPluginIPDetector[] newPlugins = new FredPluginIPDetector[plugins.length - count];
			int x = 0;
			for(int i=0;i<plugins.length;i++) {
				if(newPlugins[i] != d) newPlugins[x++] = plugins[i];
			}
			plugins = newPlugins;
		}
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
	
	private DetectorRunner runner;
	private boolean lastDetectAttemptFailed;
	private long lastDetectAttemptEndedTime;
	private long firstTimeMaybeFakePeers;
	private long firstTimeUrgent;
	
	/**
	 * Do we need to run a plugin?
	 */
	public void maybeRun() {
		Logger.minor(this, "Maybe running IP detection plugins", new Exception("debug"));
		PeerNode[] peers = node.getPeerNodes();
		PeerNode[] conns = node.getDarknetConnections();
		Peer[] nodeAddrs = node.getPrimaryIPAddress();
		synchronized(this) {
			long now = System.currentTimeMillis();
			if(runner != null) {
				Logger.minor(this, "Already running IP detection plugins");
				return;
			}
			// If detect attempt failed to produce an IP in the last 5 minutes, don't
			// try again yet.
			if(lastDetectAttemptFailed) {
				if(now - lastDetectAttemptEndedTime < 5*60*1000) {
					Logger.minor(this, "Last detect failed and more than 5 minutes ago");
					return;
				} else {
					startDetect();
				}
			}
			if(node.hasDirectlyDetectedIP()) {
				// We might still be firewalled?
				// First, check only once per day or startup
				if(now - lastDetectAttemptEndedTime < 24*60*60*1000) {
					Logger.minor(this, "Node has directly detected IP and we have checked less than 24 hours ago");
					return;
				}
				
				// Now, if we have two nodes with unique IPs which aren't ours
				// connected, we don't need to detect.
				HashSet addressesConnected = null;
				for(int i=0;i<peers.length;i++) {
					PeerNode p = peers[i];
					if(p.isConnected() || now - p.lastReceivedPacketTime() < 24*60*60*1000) {
						// Has been connected in the last 24 hours.
						// Unique IP address?
						Peer peer = p.getPeer();
						InetAddress addr = peer.getAddress(false);
						if(p.isConnected() && peer != null && addr != null && IPUtil.checkAddress(peer.getAddress())) {
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
							if(internal) {
								// Real IP address
								if(addressesConnected == null)
									addressesConnected = new HashSet();
								addressesConnected.add(addr);
								if(addressesConnected.size() > 2) {
									// 3 connected addresses, lets assume we have connectivity.
									Logger.minor(this, "Node has directly detected IP and has connected to 3 real IPs");
									return;
								}
							}
						}
					}
					long l = p.getPeerAddedTime();
					if(l <= 0 || now - l < 30*60*1000) {
						// Less than 30 minutes old, don't run a detection yet as
						// it is likely we are simply directly connected. (But we do
						// want it to work out of the box if we are not!).
						Logger.minor(this, "Not detecting as less than 30 minutes old");
						return;
					}
				}
			}
			
			if(peers.length == 0) {
				if(now - lastDetectAttemptEndedTime < 6*60*60*1000) {
					// No peers, only try every 6 hours.
					Logger.minor(this, "No peers but detected less than 6 hours ago");
					return;
				} else {
					// Must try once!
					startDetect();
					return;
				}
			} else {
				
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
							Logger.minor(this, "No connections, but have peers, may detect...");
							break;
						}
					}
				}
				
				if(maybeUrgent) {
					if(firstTimeUrgent <= 0)
						firstTimeUrgent = now;
					
					if(now - firstTimeUrgent > 2*60*1000)
						detect = true;
					
				} else {
					Logger.minor(this, "Not urgent; conns="+conns.length);
					firstTimeUrgent = 0;
				}
				
				// Do the possibly-fake-IPs detection.
				// If we have one or two peers connected now, reporting real IPs, and 
				// if there is a locally detected address they are different to it, 
				// and other peers have been connected, then maybe we need to redetect
				// to make sure we're not being spoofed.
				
				boolean maybeFake = false;
			
				if(!node.hasDirectlyDetectedIP()) {
					
					if(conns.length > 0 && conns.length < 3) {
						// No locally detected IP, only one or two connections.
						// Have we had more relatively recently?
						int count = 0;
						long timeref = now;
						if(firstTimeMaybeFakePeers > 0) timeref = firstTimeMaybeFakePeers;
						for(int i=0;i<peers.length;i++) {
							PeerNode p = peers[i];
							if((!p.isConnected()) || now - p.lastReceivedPacketTime() < 5*60*1000) {
								// Not connected now but has been within the past 5 minutes.
								count++;
							}
						}
						if(count > 2) {
							maybeFake = true;
						}
					}
				}
				
				if(maybeFake) {
					Logger.minor(this, "Possible fake IPs being fed to us, may detect...");
					if(firstTimeMaybeFakePeers <= 0)
						firstTimeMaybeFakePeers = now;
					
					if((now - firstTimeMaybeFakePeers) > 2*60*1000) {
						// MaybeFake been true for 2 minutes.
						detect = true;
					}
				
				} else {
					Logger.minor(this, "Not fake");
					firstTimeMaybeFakePeers = 0;
				}
			
				if(detect) {
					if(now - lastDetectAttemptEndedTime < 60*60*1000) {
						// Only try every hour
						Logger.minor(this, "Only trying once per hour");
						return;
					}
					
					startDetect();
				}
				
			}
		}
		
	}

	private void startDetect() {
		Logger.minor(this, "Detecting...");
		synchronized(this) {
			runner = new DetectorRunner();
			Thread t = new Thread(runner);
			t.setDaemon(true);
			t.start();
		}
	}

	public class DetectorRunner implements Runnable {

		public void run() {
			try {
				realRun();
			} catch (Throwable t) {
				Logger.error(this, "Caught "+t, t);
			}
		}
		
		public void realRun() {
			Logger.minor(this, "Running STUN detection");
			try {
			FredPluginIPDetector[] run = plugins;
			Vector v = new Vector();
			for(int i=0;i<run.length;i++) {
				DetectedIP[] detected = null;
				try {
					detected = run[i].getAddress();
				} catch (Throwable t) {
					Logger.error(this, "Caught "+t, t);
				}
				if(detected != null) {
					for(int j=0;j<detected.length;j++)
						v.add(detected[j]);
				}
			}
			synchronized(IPDetectorPluginManager.this) {
				lastDetectAttemptEndedTime = System.currentTimeMillis();
				boolean failed = false;
				if(v.isEmpty()) {
					Logger.minor(this, "No IPs found");
					failed = true;
				} else {
					failed = true;
					for(int i=0;i<v.size();i++) {
						DetectedIP ip = (DetectedIP) v.get(i);
						if(!(ip.publicAddress == null || !IPUtil.checkAddress(ip.publicAddress))) {
							Logger.minor(this, "Address checked out");
							failed = false;
						}
					}
				}
				if(failed) {
					Logger.minor(this, "Failed");
					lastDetectAttemptFailed = true;
					return;
				}
			}
			// Now tell the node
			HashMap map = new HashMap();
			for(int i=0;i<v.size();i++) {
				DetectedIP d = (DetectedIP) v.get(i);
				InetAddress addr = d.publicAddress;
				if(!map.containsKey(addr)) {
					map.put(addr, d);
				} else {
					DetectedIP oldD = (DetectedIP) map.get(addr);
					if(!oldD.equals(d)) {
						if(d.natType != DetectedIP.NOT_SUPPORTED) {
							if(oldD.natType < d.natType) {
								// Higher value = more restrictive.
								// Assume the worst.
								map.put(addr, d);
							}
						}
					}
				}
			}
			DetectedIP[] list = (DetectedIP[]) map.values().toArray(new DetectedIP[map.size()]);
			for(int i=0;i<list.length;i++)
				Logger.minor(this, "Detected IP: "+list[i].publicAddress+ " : type "+list[i].natType);
			node.processDetectedIPs(list);
			} finally {
				runner = null;
			}
		}

	}

}
