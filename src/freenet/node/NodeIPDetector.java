package freenet.node;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Vector;

import freenet.config.BooleanCallback;
import freenet.config.InvalidConfigValueException;
import freenet.config.StringCallback;
import freenet.config.SubConfig;
import freenet.io.comm.FreenetInetAddress;
import freenet.io.comm.Peer;
import freenet.node.useralerts.IPUndetectedUserAlert;
import freenet.pluginmanager.DetectedIP;
import freenet.pluginmanager.FredPluginIPDetector;
import freenet.support.Logger;
import freenet.transport.IPAddressDetector;
import freenet.transport.IPUtil;

public class NodeIPDetector {

	/** Parent node */
	final Node node;
	/** Ticker */
	final Ticker ticker;
	/** Explicit forced IP address */
	FreenetInetAddress overrideIPAddress;
	/** IP address from last time */
	FreenetInetAddress oldIPAddress;
	/** Detected IP's and their NAT status from plugins */
	DetectedIP[] pluginDetectedIPs;
	/** Last detected IP address */
	Peer[] lastIPAddress;
	/** IP address detector */
	private final IPAddressDetector ipDetector;
	/** Plugin manager for plugin IP address detectors e.g. STUN */
	private final IPDetectorPluginManager ipDetectorManager;
	/** UserAlert shown when we can't detect an IP address */
	private static IPUndetectedUserAlert primaryIPUndetectedAlert;
	// FIXME redundant? see lastIPAddress
	Peer[] lastIP;
	/** If true, include local addresses on noderefs */
	public boolean includeLocalAddressesInNoderefs;
	/** ARK inserter. */
	private final NodeARKInserter arkPutter;
	
	public NodeIPDetector(Node node) {
		this.node = node;
		this.ticker = node.ps;
		ipDetectorManager = new IPDetectorPluginManager(node, this);
		ipDetector = new IPAddressDetector(10*1000, this);
		primaryIPUndetectedAlert = new IPUndetectedUserAlert();
		arkPutter = new NodeARKInserter(node, this);
	}
	
	/**
	 * @return Our current main IP address.
	 * FIXME - we should support more than 1, and we should do the
	 * detection properly with NetworkInterface, and we should use
	 * third parties if available and UP&P if available.
	 */
	Peer[] detectPrimaryIPAddress() {
		Vector addresses = new Vector();
		if(overrideIPAddress != null) {
			// If the IP is overridden, the override has to be the first element.
			addresses.add(new Peer(overrideIPAddress, node.portNumber));
		}
	   	InetAddress[] detectedAddrs = ipDetector.getAddress();
	   	if(detectedAddrs != null) {
	   		for(int i=0;i<detectedAddrs.length;i++) {
	   			Peer p = new Peer(detectedAddrs[i], node.portNumber);
	   			if(!addresses.contains(p)) {
	   				Logger.normal(this, "Detected IP address: "+p);
	   				addresses.add(p);
	   			}
	   		}
	   	}
	   	if((pluginDetectedIPs != null) && (pluginDetectedIPs.length > 0)) {
	   		for(int i=0;i<pluginDetectedIPs.length;i++) {
	   			InetAddress addr = pluginDetectedIPs[i].publicAddress;
	   			if(addr == null) continue;
	   			Peer a = new Peer(new FreenetInetAddress(addr), node.portNumber);
	   			if(!addresses.contains(a)) {
	   				Logger.normal(this, "Plugin detected IP address: "+a);
	   				addresses.add(a);
	   			}
	   		}
	   	}
	   	if((detectedAddrs.length == 0) && (oldIPAddress != null) && !oldIPAddress.equals(overrideIPAddress))
	   		addresses.add(new Peer(oldIPAddress, node.portNumber));
   		// Try to pick it up from our connections
	   	if(node.peers != null) {
	   		PeerNode[] peerList = node.peers.connectedPeers;
	   		HashMap countsByPeer = new HashMap();
	   		// FIXME use a standard mutable int object, we have one somewhere
	   		for(int i=0;i<peerList.length;i++) {
	   			Peer p = peerList[i].getRemoteDetectedPeer();
	   			if((p == null) || p.isNull()) continue;
	   			// DNSRequester doesn't deal with our own node
	   			InetAddress ip = p.getAddress(true);
	   			if(!IPUtil.checkAddress(ip)) continue;
	   			if(countsByPeer.containsKey(ip)) {
	   				Integer count = (Integer) countsByPeer.get(p);
	   				Integer newCount = new Integer(count.intValue()+1);
	   				countsByPeer.put(p, newCount);
	   			} else {
	   				countsByPeer.put(p, new Integer(1));
	   			}
	   		}
	   		if(countsByPeer.size() == 1) {
		   		Iterator it = countsByPeer.keySet().iterator();
		   		Peer p = (Peer) (it.next());
		   		if(!addresses.contains(p)) addresses.add(p);
	   		} else if(countsByPeer.size() > 1) {
	   			Iterator it = countsByPeer.keySet().iterator();
	   			// Take two most popular addresses.
		   		Peer best = null;
		   		Peer secondBest = null;
		   		int bestPopularity = 0;
		   		int secondBestPopularity = 0;
		   		while(it.hasNext()) {
		   			Peer cur = (Peer) (it.next());
		   			int curPop = ((Integer) (countsByPeer.get(cur))).intValue();
		   			Logger.normal(this, "Detected peer: "+cur+" popularity "+curPop);
		   			if(curPop >= bestPopularity) {
		   				secondBestPopularity = bestPopularity;
		   				bestPopularity = curPop;
		   				secondBest = best;
		   				best = cur;
		   			}
		   		}
		   		if(best != null) {
		   			if((bestPopularity > 2) || (detectedAddrs.length == 0)) {
		   				if(!addresses.contains(best)) {
		   					Logger.normal(this, "Adding best peer "+best+" ("+bestPopularity+")");
		   					addresses.add(best);
		   				}
		   				if((secondBest != null) && (secondBestPopularity > 2)) {
		   					if(!addresses.contains(secondBest)) {
		   						Logger.normal(this, "Adding second best peer "+secondBest+" ("+secondBest+")");
		   						addresses.add(secondBest);
		   					}
		   				}
		   			}
		   		}
	   		}
	   	}
	   	if(node.clientCore != null) {
	   		if (addresses.isEmpty()) {
	   			node.clientCore.alerts.register(primaryIPUndetectedAlert);
	   		} else {
	   			node.clientCore.alerts.unregister(primaryIPUndetectedAlert);
	   		}
	   	}
	   	lastIPAddress = (Peer[]) addresses.toArray(new Peer[addresses.size()]);
	   	return lastIPAddress;
	}

	Peer[] getPrimaryIPAddress() {
		if(lastIPAddress == null) return detectPrimaryIPAddress();
		return lastIPAddress;
	}
	
	public boolean hasDirectlyDetectedIP() {
		InetAddress[] addrs = ipDetector.getAddress();
		return (addrs != null && addrs.length > 0);
	}

	/**
	 * Process a list of DetectedIP's from the IP detector plugin manager.
	 * DetectedIP's can tell us what kind of NAT we are behind as well as our public
	 * IP address.
	 */
	public void processDetectedIPs(DetectedIP[] list) {
		pluginDetectedIPs = list;
		redetectAddress();
		arkPutter.update();
	}

	public void redetectAddress() {
		Peer[] newIP = detectPrimaryIPAddress();
		if(Arrays.equals(newIP, lastIP)) return;
		arkPutter.update();
		lastIP = newIP;
		node.writeNodeFile();
	}

	public void setOldIPAddress(FreenetInetAddress freenetAddress) {
		this.oldIPAddress = freenetAddress;
	}

	public boolean includeLocalAddressesInNoderefs() {
		return includeLocalAddressesInNoderefs;
	}

	public int registerConfigs(SubConfig nodeConfig, int sortOrder) {
		// IP address override
		nodeConfig.register("ipAddressOverride", "", sortOrder++, true, "IP address override", "IP address override (not usually needed)", new StringCallback() {

			public String get() {
				if(overrideIPAddress == null) return "";
				else return overrideIPAddress.toString();
			}
			
			public void set(String val) throws InvalidConfigValueException {
				// FIXME do we need to tell anyone?
				if(val.length() == 0) {
					// Set to null
					overrideIPAddress = null;
					lastIPAddress = null;
					redetectAddress();
					return;
				}
				FreenetInetAddress addr;
				try {
					addr = new FreenetInetAddress(val, false);
				} catch (UnknownHostException e) {
					throw new InvalidConfigValueException("Unknown host: "+e.getMessage());
				}
				overrideIPAddress = addr;
				lastIPAddress = null;
				redetectAddress();
			}
			
		});
		
		String ipOverrideString = nodeConfig.getString("ipAddressOverride");
		if(ipOverrideString.length() == 0)
			overrideIPAddress = null;
		else {
			try {
				overrideIPAddress = new FreenetInetAddress(ipOverrideString, false);
			} catch (UnknownHostException e) {
				String msg = "Unknown host: "+ipOverrideString+" in config: "+e.getMessage();
				Logger.error(this, msg);
				System.err.println(msg+" but starting up anyway with no IP override");
				overrideIPAddress = null;
			}
		}
		
		// Temporary IP address hint
		
		nodeConfig.register("tempIPAddressHint", "", sortOrder++, false, "Temporary IP address hint", "Temporary hint to what our IP might be; deleted after use", new StringCallback() {

			public String get() {
				return "";
			}
			
			public void set(String val) throws InvalidConfigValueException {
				if(val.length() == 0) {
					return;
				}
				if(overrideIPAddress != null) return;
				try {
					oldIPAddress = new FreenetInetAddress(val, false);
				} catch (UnknownHostException e) {
					throw new InvalidConfigValueException("Unknown host: "+e.getMessage());
				}
				redetectAddress();
			}
			
		});
		
		String ipHintString = nodeConfig.getString("tempIPAddressHint");
		if(ipOverrideString.length() > 0) {
			try {
				oldIPAddress = new FreenetInetAddress(ipHintString, false);
			} catch (UnknownHostException e) {
				String msg = "Unknown host: "+ipOverrideString+" in config: "+e.getMessage();
				Logger.error(this, msg);
				System.err.println(msg+"");
				overrideIPAddress = null;
			}
		}
		
		// Include local IPs in noderef file
		
		nodeConfig.register("includeLocalAddressesInNoderefs", false, sortOrder++, true, "Include local addresses in noderef", "Whether to include local addresses (LAN and localhost) in node references. This will not be useful unless the other side sets metadata.allowLocalAddresses=true for this reference.", new BooleanCallback() {

			public boolean get() {
				return includeLocalAddressesInNoderefs;
			}

			public void set(boolean val) throws InvalidConfigValueException {
				includeLocalAddressesInNoderefs = val;
				lastIPAddress = null;
				ipDetector.clearCached();
			}
			
		});
		
		includeLocalAddressesInNoderefs = nodeConfig.getBoolean("includeLocalAddressesInNoderefs");
		
		return sortOrder;
	}

	/** Start all IP detection related processes */
	public void start() {
		ipDetectorManager.start();
		Thread t = new Thread(ipDetector, "IP address re-detector");
		t.setDaemon(true);
		t.start();
		redetectAddress();
		// 60 second delay for inserting ARK to avoid reinserting more than necessary if we don't detect IP on startup.
		ticker.queueTimedJob(new FastRunnable() {
			public void run() {
				arkPutter.start();
			}
		}, 60*1000);
	}

	public void onConnectedPeer() {
		ipDetectorManager.maybeRun();
	}

	public void registerIPDetectorPlugin(FredPluginIPDetector detector) {
		ipDetectorManager.register(detector);
	} // FIXME what about unloading?
	
}
