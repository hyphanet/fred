package freenet.node;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;

import freenet.crypt.KeyAgreementSchemeContext;
import freenet.io.AddressTracker;
import freenet.io.comm.DMT;
import freenet.io.comm.FreenetInetAddress;
import freenet.io.comm.Message;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.Peer;
import freenet.pluginmanager.MalformedPluginAddressException;
import freenet.pluginmanager.PluginAddress;
import freenet.pluginmanager.TransportPlugin;
import freenet.pluginmanager.UnsupportedIPAddressOperationException;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
/**
 * Base object for PeerPacketTransport and PeerStreamTransport. This includes common JFK fields and some others.<br><br>
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
public abstract class PeerTransport {
	
	protected final String transportName;
	
	protected final TransportPlugin transportPlugin;
	
	protected final OutgoingMangler outgoingMangler;
	
	/** We need the PeerNode as the PeerTransport is PeerNode specific, while TransportBundle is not */
	protected final PeerNode pn;
	
	/**
	 * This deals with a PeerConnection object that can handle all the keys for a transport.
	 */
	protected PeerConnection peerConn = new PeerConnection();
	
	private Object peerConnLock = new Object();
	
	/*
	 * 
	 */
	protected PluginAddress detectedTransportAddress;
	protected ArrayList<PluginAddress> nominalTransportAddress = new ArrayList<PluginAddress> ();
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
	
	/*
	* Buffer of Ni,Nr,g^i,g^r,ID
	*/
	private byte[] jfkBuffer;
	//TODO: sync ?
	
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
	/** After this many failed handshakes, we start the ARK fetcher. */
	private static final int MAX_HANDSHAKE_COUNT = 2;
	
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
	/** When did we last rekey (promote the unverified tracker to new) ? */
	long timeTransportLastRekeyed;
	protected boolean sentInitialMessagesTransport =false;
	
	/** When did we last disconnect */
	long timeTransportLastDisconnect;
	/** Previous time of disconnection */
	long timeTransportPrevDisconnect;

	/** Hold collected addresses for handshake attempts, populated by DNSRequestor
	 * Equivalent to handshakeIPs in PeerNode.
	 */
	protected PluginAddress[] handshakeTransportAddresses;
	/** The last time we attempted to update handshake Addresses */
	protected long lastAttemptedHandshakeTransportAddressUpdateTime;
	
	/* Copied from PeerNode. */
	private long lastIncomingRekey;
	static final long THROTTLE_REKEY = 1000;
	
	private int handshakeIPAlternator = 0;
	
	/** The context object for the currently running negotiation. */
	KeyAgreementSchemeContext ctxTransport;
	
	// Burst-only mode
	/** True if we are currently sending this peer a burst of handshake requests */
	private boolean isTransportBursting;
	/** Number of handshake attempts (while in ListenOnly mode) since the beginning of this burst */
	private int listeningTransportHandshakeBurstCount;
	/** Total number of handshake attempts (while in ListenOnly mode) to be in this burst */
	private int listeningTransportHandshakeBurstSize;
	
	boolean firstHandshake = true;
	
	private boolean fetchARKFlag;
	
	/** When did we last receive an ack? */
	protected long timeLastReceivedTransportAck;
	
	
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
		
		// Initialised as per PeerNode constructor.
		lastAttemptedHandshakeTransportAddressUpdateTime = 0;
	}
	
	public PeerTransport() {
		this.transportPlugin = null;
		this.outgoingMangler = null;
		this.transportName = "";
		this.pn = null;
	}
	
	public abstract void verified(SessionKey tracker);
	
	public abstract void maybeRekey();
	
	public abstract boolean disconnectTransport(boolean dumpTrackers);
	
	public synchronized long lastReceivedTransportAckTime() {
		return timeLastReceivedTransportAck;
	}
	
	public synchronized long timeLastConnectedTransport() {
		return timeLastConnectedTransport;
	}
	
	public synchronized long timeLastTransportConnectionCompleted() {
		return transportConnectedTime;
	}
	
	public synchronized void receivedAck(long now) {
		if(timeLastReceivedTransportAck < now)
			timeLastReceivedTransportAck = now;
	}
	
	public synchronized void reportIncomingBytes(int length) {
		pn.reportIncomingBytes(length);
	}

	public synchronized void reportOutgoingBytes(int length) {
		pn.reportIncomingBytes(length);
	}
	
	protected void sendTransportAddressMessage() {
		try {
			pn.sendAsync(getIPAddressMessage(), null, pn.node.nodeStats.changedIPCtr);
		} catch(NotConnectedException e) {
			Logger.normal(this, "Sending IP change message to " + this + " but disconnected: " + e, e);
		}
	}
	
	protected Message getIPAddressMessage() {
		return DMT.createFNPDetectedTransportIPAddress(detectedTransportAddress.getBytes(), transportName);
		
	}
	
	
	/*
	 * 
	 * 
	 * All methods related to handshaking grouped together. Code mostly copied from PeerNode.
	 * 
	 * 
	 */
	
	public boolean isTransportConnected() {
		long now = System.currentTimeMillis(); // no System.currentTimeMillis in synchronized
		synchronized(peerConn) {
			if(isTransportConnected && peerConn.currentTracker != null) {
				timeLastConnectedTransport = now;
				return true;
			}
			return false;
		}
	}
	
	public void startRekeying() {
		long now = System.currentTimeMillis();
		synchronized(this) {
			if(isTransportRekeying) return;
			isTransportRekeying = true;
			sendTransportHandshakeTime = now; // Immediately
			ctxTransport = null;
		}
		Logger.normal(this, "We are asking for the key to be renewed (" + this.detectedTransportAddress + ')');
	}
	
	/**
	 * Set sendHandshakeTime, and return whether to fetch the ARK.
	 */
	protected boolean innerCalcNextHandshake(boolean successfulHandshakeSend, boolean dontFetchARK, long now, boolean noLongerRoutable, boolean invalidVersion) {
		if(isBurstOnly())
			return calcNextHandshakeBurstOnly(now);
		synchronized(this) {
			long delay;
			if(noLongerRoutable) {
				// Let them know we're here, but have no hope of routing general data to them.
				delay = Node.MIN_TIME_BETWEEN_VERSION_SENDS + pn.node.random.nextInt(Node.RANDOMIZED_TIME_BETWEEN_VERSION_SENDS);
			} else if(invalidVersion && !firstHandshake) {
				delay = Node.MIN_TIME_BETWEEN_VERSION_PROBES + pn.node.random.nextInt(Node.RANDOMIZED_TIME_BETWEEN_VERSION_PROBES);
			} else {
				delay = Node.MIN_TIME_BETWEEN_HANDSHAKE_SENDS + pn.node.random.nextInt(Node.RANDOMIZED_TIME_BETWEEN_HANDSHAKE_SENDS);
			}
			// FIXME proper multi-homing support!
			delay /= (handshakeTransportAddresses == null ? 1 : handshakeTransportAddresses.length);
			if(delay < 3000) delay = 3000;
			sendTransportHandshakeTime = now + delay;
			if(logMINOR) Logger.minor(this, "Next handshake in "+delay+" on "+this);

			if(successfulHandshakeSend)
				firstHandshake = false;
			transportHandshakeCount++;
			return fetchARKFlag = (transportHandshakeCount >= MAX_HANDSHAKE_COUNT);
		}
	}
	
	private synchronized boolean calcNextHandshakeBurstOnly(long now) {
		boolean fetchARKFlag = false;
		listeningTransportHandshakeBurstCount++;
		if(isBurstOnly()) {
			if(listeningTransportHandshakeBurstCount >= listeningTransportHandshakeBurstSize) {
				listeningTransportHandshakeBurstCount = 0;
				fetchARKFlag = true;
			}
		}
		long delay;
		if(listeningTransportHandshakeBurstCount == 0) {  // 0 only if we just reset it above
			delay = Node.MIN_TIME_BETWEEN_BURSTING_HANDSHAKE_BURSTS
				+ pn.node.random.nextInt(Node.RANDOMIZED_TIME_BETWEEN_BURSTING_HANDSHAKE_BURSTS);
			listeningTransportHandshakeBurstSize = Node.MIN_BURSTING_HANDSHAKE_BURST_SIZE
					+ pn.node.random.nextInt(Node.RANDOMIZED_BURSTING_HANDSHAKE_BURST_SIZE);
			isTransportBursting = false;
		} else {
			delay = Node.MIN_TIME_BETWEEN_HANDSHAKE_SENDS
				+ pn.node.random.nextInt(Node.RANDOMIZED_TIME_BETWEEN_HANDSHAKE_SENDS);
		}
		// FIXME proper multi-homing support!
		delay /= (handshakeTransportAddresses == null ? 1 : handshakeTransportAddresses.length);
		if(delay < 3000) delay = 3000;

		sendTransportHandshakeTime = now + delay;
		if(logMINOR) Logger.minor(this, "Next BurstOnly mode handshake in "+(sendTransportHandshakeTime - now)+"ms for "+pn.shortToString()+" (count: "+listeningTransportHandshakeBurstCount+", size: "+listeningTransportHandshakeBurstSize+ ") on "+this, new Exception("double-called debug"));
		return fetchARKFlag;
	}

	/**
	* @return True, if we are disconnected and it has been a
	* sufficient time period since we last sent a handshake
	* attempt.
	*/
	public boolean shouldSendHandshake() {
		if(!pn.shouldSendHandshake())
			return false;
		long now = System.currentTimeMillis();
		boolean tempShouldSendHandshake = false;
		synchronized(this) {
			if(pn.isDisconnecting()) return false;
			tempShouldSendHandshake = ((now > sendTransportHandshakeTime) && (handshakeTransportAddresses != null) && (isTransportRekeying || !isTransportConnected()));
		}
		if(logMINOR) Logger.minor(this, "shouldSendHandshake(): initial = "+tempShouldSendHandshake);
		if(tempShouldSendHandshake && (hasLiveHandshake(now)))
			tempShouldSendHandshake = false;
		if(tempShouldSendHandshake) {
			if(isBurstOnly()) {
				synchronized(this) {
					isTransportBursting = true;
				}
				pn.setPeerNodeStatus(System.currentTimeMillis());
			} else
				return true;
		}
		if(logMINOR) Logger.minor(this, "shouldSendHandshake(): final = "+tempShouldSendHandshake);
		return tempShouldSendHandshake;
	}
	
	public long timeSendHandshake(long now) {
		if(hasLiveHandshake(now)) return Long.MAX_VALUE;
		synchronized(this) {
			if(pn.isDisconnecting()) return Long.MAX_VALUE;
			if(handshakeTransportAddresses == null) return Long.MAX_VALUE;
			if(!(isTransportRekeying || !isTransportConnected())) return Long.MAX_VALUE;
			return sendTransportHandshakeTime;
		}
	}
	
	/**
	* Does the node have a live handshake in progress?
	* @param now The current time.
	*/
	public boolean hasLiveHandshake(long now) {
		KeyAgreementSchemeContext c = null;
		synchronized(this) {
			c = ctxTransport;
		}
		if(c != null && logDEBUG)
			Logger.minor(this, "Last used (handshake): " + (now - c.lastUsedTime()));
		return !((c == null) || (now - c.lastUsedTime() > Node.HANDSHAKE_TIMEOUT));
	}
	
	/** If the outgoingMangler allows bursting, we still don't want to burst *all the time*, because it may be mistaken
	 * in its detection of a port forward. So from time to time we will aggressively handshake anyway. This flag is set
	 * once every UPDATE_BURST_NOW_PERIOD. */
	private boolean burstNow;
	private long timeSetBurstNow;
	static final int UPDATE_BURST_NOW_PERIOD = 5*60*1000;
	/** Burst only 19 in 20 times if definitely port forwarded. Save entropy by writing this as 20 not 0.95. */
	static final int P_BURST_IF_DEFINITELY_FORWARDED = 20;
	
	public boolean isBurstOnly() {
		AddressTracker.Status status = outgoingMangler.getConnectivityStatus();
		if(status == AddressTracker.Status.DONT_KNOW) return false;
		if(status == AddressTracker.Status.DEFINITELY_NATED || status == AddressTracker.Status.MAYBE_NATED) return false;

		// For now. FIXME try it with a lower probability when we're sure that the packet-deltas mechanisms works.
		if(status == AddressTracker.Status.MAYBE_PORT_FORWARDED) return false;
		long now = System.currentTimeMillis();
		if(now - timeSetBurstNow > UPDATE_BURST_NOW_PERIOD) {
			burstNow = (pn.node.random.nextInt(P_BURST_IF_DEFINITELY_FORWARDED) == 0);
			timeSetBurstNow = now;
		}
		return burstNow;
	}
	
	public synchronized KeyAgreementSchemeContext getKeyAgreementSchemeContext() {
		return ctxTransport;
	}
	
	public synchronized void setKeyAgreementSchemeContext(KeyAgreementSchemeContext ctx) {
		this.ctxTransport = ctx;
		if(logMINOR)
			Logger.minor(this, "setKeyAgreementSchemeContext(" + ctx + ") on " + this);
	}
	
	/*
	 * 
	 * 
	 * End of handshaking related methods
	 * 
	 * 
	 */
	
	/**
	 * 
	 * @param physical
	 * @param setDetected If true, it is assumed that it is the metadata information and we can directly set
	 * detectedTransportAddress.
	 * @param purgeAddresses If true it will get rid of all previous addresses.
	 * Use it only if a new noderef has been obtained
	 */
	public void setTransportAddress(HashSet<String> physical, boolean setDetected, boolean purgeAddresses) {
		if(purgeAddresses) {
			nominalTransportAddress.clear();
			detectedTransportAddress = null;
			// Since it is purging, we assume there has been a handshake
			lastAttemptedHandshakeTransportAddressUpdateTime = 0;
			// Clear nonces to prevent leak. Will kill any in-progress connect attempts, but that is okay because
			// either we got an ARK which changed our peers list, or we just connected.
			jfkNoncesSent.clear();
		}
		for(String address : physical) {
			PluginAddress pluginAddress;
			try {
				pluginAddress = transportPlugin.toPluginAddress(address);
				if(!nominalTransportAddress.contains(pluginAddress))
					nominalTransportAddress.add(pluginAddress);
				if(setDetected)
					detectedTransportAddress = pluginAddress;
			} catch (MalformedPluginAddressException e) {
				continue;
				//FIXME Do we throw an FSParseException at the PeerNode constructor?
			}
		}
		if(!nominalTransportAddress.isEmpty() && !setDetected)
			detectedTransportAddress = nominalTransportAddress.get(0);
	}
	
	/**
	 * Normally this is the address that data has been received from.
	 * However, if ignoreSourcePort is set, we will search for a similar address with a different port
	 * number in the node reference.
	 * Only for IP based addresses the above is applicable.
	 */
	public synchronized PluginAddress getAddress() {
		try {
			if(pn.isIgnoreSource()) {
				FreenetInetAddress addr = detectedTransportAddress == null ? null : detectedTransportAddress.getFreenetAddress();
				int port = detectedTransportAddress == null ? -1 : detectedTransportAddress.getPortNumber();
				if(nominalTransportAddress == null) return detectedTransportAddress;
				for(PluginAddress address : nominalTransportAddress) {
					try {
						if(address.getPortNumber() != port && address.getFreenetAddress().equals(addr)) {
							return address;
						}
					} catch(NullPointerException e) {
						// What if address is null or address.getFreenetAddress() is null
						continue;
					}
				}
			}
		}catch(UnsupportedIPAddressOperationException e){
			//Do nothing
		}
		return detectedTransportAddress;
	}
	
	public void changedAddress(PluginAddress newAddress) {
		setDetectedAddress(newAddress);
	}
	
	/**
	 * Use this to set detectedTransportAddress.
	 * Used when we have detected an address from the incoming messages.
	 * This is the address we reply to.
	 * @param newAddress
	 */
	protected void setDetectedAddress(PluginAddress newAddress) {
		try {
			newAddress = newAddress.dropHostName();
		}catch(UnsupportedIPAddressOperationException e) {
			//Non IP based address
		}
		synchronized(this) {
			PluginAddress oldAddress = detectedTransportAddress;
			if((newAddress != null) && ((oldAddress == null) || !oldAddress.equals(newAddress))) {
				detectedTransportAddress = newAddress;
				// IP has changed, it is worth looking up the DNS address again.
				lastAttemptedHandshakeTransportAddressUpdateTime = 0;
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
	* Ported from PeerNode. Uses PluginAddress instead of Peer
	*/
	public PluginAddress[] updateHandshakeAddresses(PluginAddress[] localHandshakeAddresses, boolean ignoreHostnames) {
		if(localHandshakeAddresses == null)
			return null;
		HashSet<PluginAddress> ret = new HashSet<PluginAddress>();
		for(PluginAddress localHandshakeAddress : localHandshakeAddresses) {
				if(logMINOR)
					Logger.debug(this, "updateHandshakeAddresses on PeerTransport" + localHandshakeAddress);
				try {
					if(!ignoreHostnames)
						localHandshakeAddress.updateHostName();
					// De-duplicate
					PluginAddress tempAddress = localHandshakeAddress.dropHostName();
					if(tempAddress != null)
						ret.add(tempAddress);
				}catch(UnsupportedIPAddressOperationException e) {
					if(logMINOR)
						Logger.debug(this, "Not IP based" + localHandshakeAddress, e);
				}
		}
		
		return ret.toArray(new PluginAddress[ret.size()]);
	}
	
	/**
	* Do occasional DNS requests, but ignoreHostnames should be true
	* on PeerNode construction
	*/
	public void maybeUpdateHandshakeAddresses(boolean ignoreHostnames) {
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
			localHandshakeAddresses = updateHandshakeAddresses(localHandshakeAddresses, ignoreHostnames);
			synchronized(this) {
				handshakeTransportAddresses = localHandshakeAddresses;
			}
			return;
		}

		// Hack for two nodes on the same IP that can't talk over inet for routing reasons
		
		FreenetInetAddress localhost = pn.node.fLocalhostAddress;
		//Peer[] nodePeers = outgoingMangler.getPrimaryIPAddress();
		Peer[] nodePeers = null; //FIXME finish this part

		ArrayList<PluginAddress> localAddresses = null;
		synchronized(this) {
			localAddresses = new ArrayList<PluginAddress>(nominalTransportAddress);
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
				if(addr == null)
					continue;
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
		}catch(UnsupportedIPAddressOperationException e) {
			
		}
		localHandshakeAddresses = localAddresses.toArray(new PluginAddress[localAddresses.size()]);
		localHandshakeAddresses = updateHandshakeAddresses(localHandshakeAddresses, ignoreHostnames);
		synchronized(this) {
			handshakeTransportAddresses = localHandshakeAddresses;
			if((detectedDuplicate != null) && detectedDuplicate.equals(localDetectedAddress))
				localDetectedAddress = detectedTransportAddress = detectedDuplicate;
		}
	}
	
	//FIXME Find the place where it needs to be used.
	private String handshakeAddressesToString() {

		PluginAddress[] localHandshakeAddresses;
		synchronized(this) {
			localHandshakeAddresses = handshakeTransportAddresses;
		}
		if(localHandshakeAddresses == null)
			return "null";
		StringBuilder toOutputString = new StringBuilder(1024);
		toOutputString.append("[ ");
		if (localHandshakeAddresses.length != 0) {
			for(PluginAddress localAddress: localHandshakeAddresses) {
				if(localAddress == null) {
					toOutputString.append("null, ");
					continue;
				}
				toOutputString.append('\'');
				toOutputString.append(localAddress);
				toOutputString.append('\'');
				toOutputString.append(", ");
			}
			toOutputString.deleteCharAt(toOutputString.length() - 1);
			toOutputString.deleteCharAt(toOutputString.length() - 1);
		}
		toOutputString.append(" ]");
		return toOutputString.toString();
	}
	
	public PluginAddress[] getHandshakeAddresses() {
		return handshakeTransportAddresses;
	}
	
	public PluginAddress getHandshakeAddress() {
		PluginAddress[] localHandshakeAddresses;
		if(!shouldSendHandshake()) {
			if(logMINOR) Logger.minor(this, "Not sending handshake to "+detectedTransportAddress+" because pn.shouldSendHandshake() returned false");
			return null;
		}
		long firstTime = System.currentTimeMillis();
		localHandshakeAddresses = getHandshakeAddresses();
		long secondTime = System.currentTimeMillis();
		if((secondTime - firstTime) > 1000)
			Logger.error(this, "getHandshakeIPs() took more than a second to execute ("+(secondTime - firstTime)+") working on "+detectedTransportAddress);
		if(localHandshakeAddresses.length == 0) {
			long thirdTime = System.currentTimeMillis();
			if((thirdTime - secondTime) > 1000)
				Logger.error(this, "couldNotSendHandshake() (after getHandshakeIPs()) took more than a second to execute ("+(thirdTime - secondTime)+") working on "+detectedTransportAddress);
			return null;
		}
		long loopTime1 = System.currentTimeMillis();
		ArrayList<PluginAddress> validIPs = new ArrayList<PluginAddress>();
		for(PluginAddress localHandshakeAddress : localHandshakeAddresses){
			PluginAddress address = localHandshakeAddress;
			try {
				FreenetInetAddress addr = address.getFreenetAddress();
				if(!outgoingMangler.allowConnection(pn, addr)) {
					if(logMINOR)
						Logger.minor(this, "Not sending handshake packet to "+address+" for "+this);
				}
				if(addr.getAddress(false) == null) {
					if(logMINOR) Logger.minor(this, "Not sending handshake to "+localHandshakeAddress+" for "+detectedTransportAddress+" because the DNS lookup failed or it's a currently unsupported IPv6 address");
					continue;
				}
				if(!addr.isRealInternetAddress(false, false, outgoingMangler.alwaysAllowLocalAddresses())) {
					if(logMINOR) Logger.minor(this, "Not sending handshake to "+localHandshakeAddress+" for "+detectedTransportAddress+" because it's not a real Internet address and metadata.allowLocalAddresses is not true");
					continue;
				}
			}catch (UnsupportedIPAddressOperationException e) {
				// We assume for non ip based addresses we don't need to check the above
			}
			validIPs.add(address);
		}
		PluginAddress ret;
		if(validIPs.isEmpty()) {
			ret = null;
		} else if(validIPs.size() == 1) {
			ret = validIPs.get(0);
		} else {
			// Don't need to synchronize for this value as we're only called from one thread anyway.
			handshakeIPAlternator %= validIPs.size();
			ret = validIPs.get(handshakeIPAlternator);
			handshakeIPAlternator++;
		}
		long loopTime2 = System.currentTimeMillis();
		if((loopTime2 - loopTime1) > 1000)
			Logger.normal(this, "loopTime2 is more than a second after loopTime1 ("+(loopTime2 - loopTime1)+") working on "+detectedTransportAddress);
		return ret;
	}
	
	public synchronized boolean getSentInitialMessageStatus() {
		return sentInitialMessagesTransport;
	}
	
	public boolean shouldSendInitialMessages() {
		synchronized(this) {
			if(sentInitialMessagesTransport)
				return false;
			if(getCurrentKeyTracker() != null) {
				sentInitialMessagesTransport = true;
				return true;
			}
			else
				return false;
		}
	}
	
	/*
	 * 
	 * Some getter/setter methods
	 * 
	 */
	protected byte[] getJFKBuffer() {
		return jfkBuffer;
	}

	protected void setJFKBuffer(byte[] bufferJFK) {
		this.jfkBuffer = bufferJFK;
	}
	
	public boolean getFetchARKFlag() {
		return fetchARKFlag;
	}
	
	/**
	* @return The current primary SessionKey, or null if we
	* don't have one.
	*/
	public SessionKey getCurrentKeyTracker() {
		synchronized(peerConnLock) {
			return peerConn.currentTracker;
		}
	}
	
	/**
	* @return The previous primary SessionKey, or null if we
	* don't have one.
	*/
	public SessionKey getPreviousKeyTracker() {
		synchronized(peerConnLock) {
			return peerConn.previousTracker;
		}			
	}
	
	/**
	* @return The unverified SessionKey, if any, or null if we
	* don't have one. The caller MUST call verified(KT) if a
	* decrypt succeeds with this KT.
	*/
	public SessionKey getUnverifiedKeyTracker() {
		synchronized(peerConnLock) {
			return peerConn.unverifiedTracker;
		}
	}
	
	public boolean isTransportBursting() {
		return isTransportBursting;
	}
	
	/** 
	 * Is this peer allowed local addresses? If false, we will never connect to this peer via
	 * a local address even if it advertises them.
	 */
	public boolean allowLocalAddresses() {
		synchronized(this) {
			if(pn.allowLocalAddress()) return true;
		}
		return outgoingMangler.alwaysAllowLocalAddresses();
	}
	
	public void sendHandshake(boolean notRegistered){
		outgoingMangler.sendHandshake(pn, notRegistered);
	}
	
	public String userToString() {
		return "" + detectedTransportAddress;
	}
	
	/**
	 * Base object of PeerPacketConnection and PeerStreamConnection. Fields common to both are present.
	 * @author chetan
	 *
	 */
	static class PeerConnection {
		
		/** How much data did we send with the current tracker ? */
		public long totalBytesExchangedWithCurrentTracker = 0;
		
		public SessionKey currentTracker = null;
		public SessionKey previousTracker = null;
		public SessionKey unverifiedTracker = null;
		
		static final byte[] TEST_AS_BYTES = PeerNode.TEST_AS_BYTES;
		static final int CHECK_FOR_SWAPPED_TRACKERS_INTERVAL = PeerNode.CHECK_FOR_SWAPPED_TRACKERS_INTERVAL;
		
	}
		
}
