package freenet.node;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Vector;

import freenet.io.comm.DMT;
import freenet.io.comm.FreenetInetAddress;
import freenet.io.comm.Message;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.Peer;
import freenet.pluginmanager.PluginAddress;
import freenet.pluginmanager.TransportPlugin;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
/**
 * Base object for PeerPacketTransport and PeerStreamTransport. This includes common JKF fields and some others.<br><br>
 * 
 *  * <b>Convention:</b> The "Transport" word is used in fields that are transport specific, and are also present in PeerNode.
 * These fields will allow each Transport to behave differently. The existing fields in PeerNode will be used for 
 * common functionality.
 * The fields without "Transport" in them are those which in the long run must be removed from PeerNode.
 * <br> e.g.: <b>isTransportRekeying</b> is used if the individual transport is rekeying;
 * <b>isRekeying</b> will be used in common to all transports in PeerNode.
 * <br> e.g.: <b>jfkKa</b>, <b>incommingKey</b>, etc. should be transport specific and must be moved out of PeerNode 
 * once existing UDP is fully converted to the new TransportPlugin format.
 * @author chetan
 *
 */
public class PeerTransport {
	
	protected final String transportName;
	
	protected final TransportPlugin transportPlugin;
	
	protected final OutgoingMangler outgoingMangler;
	
	/** We need the PeerNode as the PeerTransport is PeerNode specific, while TransportBundle is not */
	protected final PeerNode pn;
	
	/*
	 * 
	 */
	protected PluginAddress detectedTransportAddress;
	protected Vector<PluginAddress> nominalTransportAddress = new Vector<PluginAddress> ();
	protected PluginAddress remoteDetectedTransportAddress;
	
	/*
	 * JFK specific fields.
	 */
	protected byte[] jfkKa;
	protected byte[] incommingKey;
	protected byte[] jfkKe;
	protected byte[] outgoingKey;
	protected byte[] jfkMyRef;
	protected byte[] hmacKey;
	protected byte[] ivKey;
	protected byte[] ivNonce;
	protected int ourInitialSeqNum;
	protected int theirInitialSeqNum;
	protected int ourInitialMsgID;
	protected int theirInitialMsgID;
	
	protected long jfkContextLifetime = 0;
	
	/**
	 * For FNP link setup:
	 *  The initiator has to ensure that nonces send back by the
	 *  responder in message2 match what was chosen in message 1
	 */
	protected final LinkedList<byte[]> jfkNoncesSent = new LinkedList<byte[]>();
	
	protected boolean isTransportConnected;
	/** Are we rekeying ? */
	protected boolean isTransportRekeying = false;
	/** Number of handshake attempts since last successful connection or ARK fetch */
	protected int transportHandshakeCount;
	
	/** Transport input */
	protected long totalTransportInputSinceStartup;
	/** Transport output */
	protected long totalTransportOutputSinceStartup;
	
	/** The time at which we last completed a connection setup for this transport. */
	protected long transportConnectedTime;
	/** When was isConnected() last true? */
	protected long timeLastConnectedTransport;
	/** Time added or restarted (reset on startup unlike peerAddedTime) */
	protected long timeAddedOrRestartedTransport;
	/** Time at which we should send the next handshake request */
	protected long sendTransportHandshakeTime;
	
	/** Hold collected IP addresses for handshake attempts, populated by DNSRequestor
	 * Equivalent to handshakeIPs in PeerNode.
	 */
	protected PluginAddress[] handshakeTransportAddresses;
	/** The last time we attempted to update handshake Addresses */
	protected long lastAttemptedHandshakeTransportAddressUpdateTime;
	
	/* Copied from PeerNode. */
	private long lastIncomingRekey;
	static final long THROTTLE_REKEY = 1000;
	
	
	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
				logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
			}
		});
	}
	
	public PeerTransport(TransportPlugin transportPlugin, OutgoingMangler outgoingMangler, PeerNode pn){
		this.transportPlugin = transportPlugin;
		this.outgoingMangler = outgoingMangler;
		this.transportName = transportPlugin.transportName;
		this.pn = pn;
	}
	
	protected void sendTransportAddressMessage() {
		try {
			pn.sendAsync(getIPAddressMessage(), null, pn.node.nodeStats.changedIPCtr);
		} catch(NotConnectedException e) {
			Logger.normal(this, "Sending IP change message to " + this + " but disconnected: " + e, e);
		}
	}
	
	protected Message getIPAddressMessage() {
		return DMT.createFNPDetectedTransportIPAddress(detectedTransportAddress, transportName);
		
	}
	
	public void setTransportAddress(String[] physical, boolean fromLocal, boolean checkHostnameOrIPSyntax) {
		
	}
	
	protected void setDetectedAddress(PluginAddress newAddress) {
		try {
			newAddress.updateHostname();
		}catch(UnsupportedOperationException e) {
			//Non IP based address
		}
		synchronized(this) {
			PluginAddress oldAddress = detectedTransportAddress;
			if((newAddress != null) && ((oldAddress == null) || !oldAddress.equals(newAddress))) {
				this.detectedTransportAddress = newAddress;
				this.lastAttemptedHandshakeTransportAddressUpdateTime = 0;
				if(!isTransportConnected)
					return;
			} else
				return;
		}
		pn.getThrottle().maybeDisconnected();
		sendTransportAddressMessage();
	}
	
	public synchronized boolean throttleRekey() {
		long now = System.currentTimeMillis();
		if(now - lastIncomingRekey < THROTTLE_REKEY) {
			Logger.error(this, "Two rekeys initiated by other side within "+THROTTLE_REKEY+"ms");
			return true;
		}
		lastIncomingRekey = now;
		return false;
	}
	
	/**
	* Do the maybeUpdateHandshakeIPs DNS requests, but only if ignoreHostnames is false
	* This method should only be called by maybeUpdateHandshakeIPs.
	* Also removes dupes post-lookup.
	*/
	public PluginAddress[] updateHandshakeAddresses(PluginAddress[] localHandshakeAddresses) {
		for(PluginAddress localHandshakeAddress : localHandshakeAddresses) {
				if(logMINOR)
					Logger.debug(this, "updateHandshakeAddresses on PeerTransport" + localHandshakeAddress);
				try {
					localHandshakeAddress.updateHostname();
				}catch(UnsupportedOperationException e) {
					if(logMINOR)
						Logger.debug(this, "Not IP based" + localHandshakeAddress, e);
				}
		}
		// De-dupe
		HashSet<PluginAddress> ret = new HashSet<PluginAddress>();
		for(PluginAddress localHandshakeAddress : localHandshakeAddresses)
			ret.add(localHandshakeAddress);
		return ret.toArray(new PluginAddress[ret.size()]);
	}
	
	/**
	* Do occasional DNS requests, but ignoreHostnames should be true
	* on PeerNode construction
	*/
	public void maybeUpdateHandshakeIPs(boolean ignoreHostnames) {
		long now = System.currentTimeMillis();
		PluginAddress localDetectedAddress = null;
		synchronized(this) {
			localDetectedAddress = detectedTransportAddress;
			if((now - lastAttemptedHandshakeTransportAddressUpdateTime) < (5 * 60 * 1000)) {  // 5 minutes
				return;
			}
			// We want to come back right away for DNS requesting if this is our first time through
			if(!ignoreHostnames)
				lastAttemptedHandshakeTransportAddressUpdateTime = now;
		}
		PluginAddress[] myNominalAddress;

		// Don't synchronize while doing lookups which may take a long time!
		synchronized(this) {
			myNominalAddress = nominalTransportAddress.toArray(new PluginAddress[nominalTransportAddress.size()]);
		}

		PluginAddress[] localHandshakeAddresses;
		if(myNominalAddress.length == 0) {
			if(localDetectedAddress == null) {
				synchronized(this) {
					handshakeTransportAddresses = null;
				}
				return;
			}
			localHandshakeAddresses = new PluginAddress[]{localDetectedAddress};
			localHandshakeAddresses = updateHandshakeAddresses(localHandshakeAddresses);
			synchronized(this) {
				handshakeTransportAddresses = localHandshakeAddresses;
			}
			return;
		}

		// Hack for two nodes on the same IP that can't talk over inet for routing reasons
		
		FreenetInetAddress localhost = pn.node.fLocalhostAddress;
		//Peer[] nodePeers = outgoingMangler.getPrimaryIPAddress();
		Peer[] nodePeers = null; //FIXME finish this part

		Vector<PluginAddress> localAddresses = null;
		synchronized(this) {
			localAddresses = new Vector<PluginAddress>(nominalTransportAddress);
		}

		boolean addedLocalhost = false;
		PluginAddress detectedDuplicate = null;
		try {
			for(PluginAddress p : myNominalAddress) {
				if(p == null)
					continue;
				if(localDetectedAddress != null) {
					if((p != localDetectedAddress) && p.equals(localDetectedAddress)) {
						// Equal but not the same object; need to update the copy.
						detectedDuplicate = p;
					}
				}
				FreenetInetAddress addr = p.getFreenetAddress();
				if(addr.equals(localhost)) {
					if(addedLocalhost)
						continue;
					addedLocalhost = true;
				}
				for(Peer peer : nodePeers) {
					// REDFLAG - Two lines so we can see which variable is null when it NPEs
					FreenetInetAddress myAddr = peer.getFreenetAddress();
					if(myAddr.equals(addr)) {
						if(!addedLocalhost)
							localAddresses.add(new PeerPluginAddress(localhost, p.getPortNumber()));
						addedLocalhost = true;
					}
				}
				if(localAddresses.contains(p))
					continue;
				localAddresses.add(p);
			}
		}catch(UnsupportedOperationException e) {
			
		}
		localHandshakeAddresses = localAddresses.toArray(new PluginAddress[localAddresses.size()]);
		localHandshakeAddresses = updateHandshakeAddresses(localHandshakeAddresses);
		synchronized(this) {
			handshakeTransportAddresses = localHandshakeAddresses;
			if((detectedDuplicate != null) && detectedDuplicate.equals(localDetectedAddress))
				localDetectedAddress = detectedTransportAddress = detectedDuplicate;
		}
	}
	
	
	
}
