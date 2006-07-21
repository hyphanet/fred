package freenet.node;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.net.MalformedURLException;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.Vector;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import freenet.crypt.BlockCipher;
import freenet.crypt.DiffieHellmanContext;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.ciphers.Rijndael;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.FreenetInetAddress;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.Peer;
import freenet.io.comm.PeerContext;
import freenet.io.comm.PeerParseException;
import freenet.keys.ClientSSK;
import freenet.keys.FreenetURI;
import freenet.keys.USK;
import freenet.support.Base64;
import freenet.support.Fields;
import freenet.support.HexUtil;
import freenet.support.IllegalBase64Exception;
import freenet.support.LRUHashtable;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.WouldBlockException;
import freenet.support.math.RunningAverage;
import freenet.support.math.SimpleRunningAverage;
import freenet.support.math.TimeDecayingRunningAverage;

/**
 * @author amphibian
 * 
 * Represents a peer we are connected to. One of the major issues
 * is that we can rekey, or a node can go down and come back up
 * while we are connected to it, and we want to reinitialize the
 * packet numbers when this happens. Hence we separate a lot of
 * code into KeyTracker, which handles all communications to and
 * from this peer over the duration of a single key.
 */
public class PeerNode implements PeerContext {

    /** Set to true when we complete a handshake. */
    private boolean completedHandshake;
    
    private String lastGoodVersion; 
	
    /** Set to true based on a relevant incoming handshake from this peer
     *  Set true if this peer has a incompatible older build than we are
     */
    private boolean verifiedIncompatibleOlderVersion;
	
    /** Set to true based on a relevant incoming handshake from this peer
     *  Set true if this peer has a incompatible newer build than we are
     */
    private boolean verifiedIncompatibleNewerVersion;
	
    /** For debugging/testing, set this to true to stop the
     * probabilistic decrement at the edges of the HTLs.
     */
    static boolean disableProbabilisticHTLs;

    /** My low-level address for SocketManager purposes */
    private Peer detectedPeer;
    
    /** Advertised addresses */
    private Vector nominalPeer;
    
    /** The PeerNode's report of our IP address */
    private Peer remoteDetectedPeer;
    
    /** Is this a testnet node? */
    public final boolean testnetEnabled;
    
    /** Name of this node */
    String myName;
    
    /** Packets sent/received on the current preferred key */
    private KeyTracker currentTracker;
    
    /** Previous key - has a separate packet number space */
    private KeyTracker previousTracker;
    
    /** Unverified tracker - will be promoted to currentTracker if
     * we receive packets on it
     */
    private KeyTracker unverifiedTracker;
    
    /** When did we last send a packet? */
    private long timeLastSentPacket;
       
    /** When did we last receive a packet? */
    private long timeLastReceivedPacket;
       
    /** When was isConnected() last true? */
    private long timeLastConnected;
       
    /** When was isRoutingCompatible() last true? */
    private long timeLastRoutable;
    
    /** Are we connected? If not, we need to start trying to
     * handshake.
     */
    private boolean isConnected;
    private boolean isRoutable;
    /**
     * ARK fetcher.
     */
    private final ARKFetcher arkFetcher;
    
    /** My ARK SSK public key; edition is the next one, not the current one, 
     * so this is what we want to fetch. */
    private USK myARK;
    
    /** Number of handshake attempts since last successful connection or ARK fetch */
    private int handshakeCount;
    
    /** After this many failed handshakes, we start the ARK fetcher. */
    private static final int MAX_HANDSHAKE_COUNT = 2;
    
    /** Number of handshake attempts (while in ListenOnly mode) since the beginning of this burst */
    private int listeningHandshakeBurstCount;
    
    /** Total number of handshake attempts (while in ListenOnly mode) to be in this burst */
    private int listeningHandshakeBurstSize;
    
    /** Current location in the keyspace */
    private Location currentLocation;
    
    /** Node identity; for now a block of data, in future a
     * public key (FIXME). Cannot be changed.
     */
    final byte[] identity;
    
    /** Hash of node identity. Used as setup key. */
    final byte[] identityHash;
    
    /** Integer hash of node identity. Used as hashCode(). */
    final int hashCode;
    
    /** The Node we serve */
    final Node node;
    
    /** MessageItem's to send ASAP */
    private final LinkedList messagesToSendNow;
    
    /** When did we last receive a SwapRequest? */
    private long timeLastReceivedSwapRequest;
    
    /** Average interval between SwapRequest's */
    private final RunningAverage swapRequestsInterval;
    
    /** Should we decrement HTL when it is at the maximum? 
     * This decision is made once per node to prevent giving
     * away information that can make correlation attacks much
     * easier.
     */
    final boolean decrementHTLAtMaximum;
    
    /** Should we decrement HTL when it is at the minimum (1)? */
    final boolean decrementHTLAtMinimum;

    /** Time at which we should send the next handshake request */
    private long sendHandshakeTime;
    
    /** Time after which we log message requeues while rate limiting */
    private long nextMessageRequeueLogTime;
    
    /** Interval between rate limited message requeue logs (in milliseconds) */
    private long messageRequeueLogRateLimitInterval = 1000;
    
    /** Number of messages to be requeued after which we rate limit logging of such */
    private int messageRequeueLogRateLimitThreshold = 15;
    
    /** Version of the node */
    private String version;
    
    /** Incoming setup key. Used to decrypt incoming auth packets.
     * Specifically: K_node XOR H(setupKey).
     */
    final byte[] incomingSetupKey;
    
    /** Outgoing setup key. Used to encrypt outgoing auth packets.
     * Specifically: setupKey XOR H(K_node).
     */
    final byte[] outgoingSetupKey;
    
    /** Incoming setup cipher (see above) */
    final BlockCipher incomingSetupCipher;
    
    /** Outgoing setup cipher (see above) */
    final BlockCipher outgoingSetupCipher;
    
    /** The context object for the currently running negotiation. */
    private DiffieHellmanContext ctx;
    
    /** The other side's boot ID. This is a random number generated
     * at startup.
     */
    private long bootID;

    /** If true, this means last time we tried, we got a bogus noderef */
    private boolean bogusNoderef;
    
    /** The time at which we last completed a connection setup. */
    private long connectedTime;
    
    /** The status of this peer node in terms of Node.PEER_NODE_STATUS_* */
    public int peerNodeStatus = Node.PEER_NODE_STATUS_DISCONNECTED;

    /** Holds a String-Long pair that shows which message types (as name) have been send to this peer. */
    private Hashtable localNodeSentMessageTypes = new Hashtable();
    
    /** Holds a String-Long pair that shows which message types (as name) have been received by this peer. */
    private Hashtable localNodeReceivedMessageTypes = new Hashtable();

    /** Hold collected IP addresses for handshake attempts, populated by DNSRequestor */
    private Peer[] handshakeIPs;
    
    /** The last time we attempted to update handshakeIPs */
    private long lastAttemptedHandshakeIPUpdateTime;
    
    /** True if we have never connected to this peer since it was added to this node */
    private boolean neverConnected;
    
    /** When this peer was added to this node */
    private long peerAddedTime = 1;
    
    /** True if this peer is not to be connected with */
    private boolean isDisabled;
    
    /** True if we don't send handshake requests to this peer, but will connect if we receive one */
    private boolean isListenOnly;
    
    /** True if we send handshake requests to this peer in infrequent bursts */
    private boolean isBurstOnly;
    
    /** True if we are currently sending this peer a burst of handshake requests */
    private boolean isBursting;
    
    /** True if we want to allow LAN/localhost addresses. */
    private boolean allowLocalAddresses;

    /**
     * Create a PeerNode from a SimpleFieldSet containing a
     * node reference for one. This must contain the following
     * fields:
     * - identity
     * - version
     * - location
     * - physical.udp
     * - setupKey
     * Do not add self to PeerManager.
     * @param fs The SimpleFieldSet to parse
     * @param node2 The running Node we are part of.
     */
    public PeerNode(SimpleFieldSet fs, Node node2, boolean fromLocal) throws FSParseException, PeerParseException {
        this.node = node2;
        boolean base64 = Fields.stringToBool(fs.get("base64"), false);
        String identityString = fs.get("identity");
    	if(identityString == null)
    		throw new PeerParseException("No identity!");
        try {
        	if(base64)
        		identity = Base64.decode(identityString);
        	else
        		identity = HexUtil.hexToBytes(identityString);
        } catch (NumberFormatException e) {
            throw new FSParseException(e);
        } catch (IllegalBase64Exception e) {
            throw new FSParseException(e);
		}
        
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e2) {
            throw new Error(e2);
        }
        
        if(identity == null) throw new FSParseException("No identity");
        identityHash = md.digest(identity);
        hashCode = Fields.hashCode(identityHash);
        version = fs.get("version");
        Version.seenVersion(version);
        String locationString = fs.get("location");
        if(locationString == null) throw new FSParseException("No location");
        currentLocation = new Location(locationString);

        // FIXME make mandatory once everyone has upgraded
        lastGoodVersion = fs.get("lastGoodVersion");
        
        String name = fs.get("myName");
        if(name == null) throw new FSParseException("No name");
        myName = name;
        String testnet = fs.get("testnet");
        testnetEnabled = testnet == null ? false : (testnet.equalsIgnoreCase("true") || testnet.equalsIgnoreCase("yes"));
        if(testnetEnabled != node.testnetEnabled) {
        	String err = "Ignoring incompatible node "+detectedPeer+" - peer.testnet="+testnetEnabled+"("+testnet+") but node.testnet="+node.testnetEnabled;
        	Logger.error(this, err);
        	throw new PeerParseException(err);
        }
        
        nominalPeer=new Vector();
        nominalPeer.removeAllElements();
        try{
        	String physical[]=fs.getAll("physical.udp");
        	if(physical==null){
        		// Be tolerant of nonexistent domains.
        		Peer p = new Peer(fs.get("physical.udp"), true);
        		if(p != null)
        			nominalPeer.addElement(p);
        	}else{
	    		for(int i=0;i<physical.length;i++){		
					Peer p = new Peer(physical[i], true);
				    if(!nominalPeer.contains(p)) 
				    	nominalPeer.addElement(p);
	    		}
        	}
        } catch (Exception e1) {
                throw new FSParseException(e1);
        }
        if(nominalPeer.isEmpty()) {
        	Logger.normal(this, "No IP addresses found for identity '"+Base64.encode(identity)+"', possibly at location '"+Double.toString(currentLocation.getValue())+"' with name '"+getName()+"'");
        	detectedPeer = null;
        } else {
        	detectedPeer = (Peer) nominalPeer.firstElement();
        }
        
        // Setup incoming and outgoing setup ciphers
        byte[] nodeKey = node.identityHash;
        byte[] nodeKeyHash = node.identityHashHash;
        byte[] setupKeyHash = md.digest(identityHash);
        
        int digestLength = md.getDigestLength();
        incomingSetupKey = new byte[digestLength];
        for(int i=0;i<incomingSetupKey.length;i++)
            incomingSetupKey[i] = (byte) (nodeKey[i] ^ setupKeyHash[i]);
        outgoingSetupKey = new byte[digestLength];
        for(int i=0;i<outgoingSetupKey.length;i++)
            outgoingSetupKey[i] = (byte) (nodeKeyHash[i] ^ identityHash[i]);
        Logger.minor(this, "Keys:\nIdentity:  "+HexUtil.bytesToHex(node.myIdentity)+
                                "\nThisIdent: "+HexUtil.bytesToHex(identity)+
        		                "\nNode:      "+HexUtil.bytesToHex(nodeKey)+
                                "\nNode hash: "+HexUtil.bytesToHex(nodeKeyHash)+
                                "\nThis:      "+HexUtil.bytesToHex(identityHash)+
                                "\nThis hash: "+HexUtil.bytesToHex(setupKeyHash)+
                                "\nFor:       "+getDetectedPeer());
        
        try {
            incomingSetupCipher = new Rijndael(256,256);
            incomingSetupCipher.initialize(incomingSetupKey);
            outgoingSetupCipher = new Rijndael(256,256);
            outgoingSetupCipher.initialize(outgoingSetupKey);
        } catch (UnsupportedCipherException e1) {
            Logger.error(this, "Caught: "+e1);
            throw new Error(e1);
        }
        
        // Don't create trackers until we have a key
        currentTracker = null;
        previousTracker = null;
        
        timeLastSentPacket = -1;
        timeLastReceivedPacket = -1;
        timeLastReceivedSwapRequest = -1;
        timeLastConnected = -1;
        timeLastRoutable = -1;
        
        randomizeMaxTimeBetweenPacketSends();
        swapRequestsInterval = new SimpleRunningAverage(50, Node.MIN_INTERVAL_BETWEEN_INCOMING_SWAP_REQUESTS);
        
        // Not connected yet; need to handshake
        isConnected = false;
        node.addPeerNodeStatus(Node.PEER_NODE_STATUS_DISCONNECTED, this);
               
        messagesToSendNow = new LinkedList();
        
        decrementHTLAtMaximum = node.random.nextFloat() < Node.DECREMENT_AT_MAX_PROB;
        decrementHTLAtMinimum = node.random.nextFloat() < Node.DECREMENT_AT_MIN_PROB;

        pingNumber = node.random.nextLong();
        
        // A SimpleRunningAverage would be a bad choice because it would cause oscillations.
        // So go for a filter.
        pingAverage = 
        	new TimeDecayingRunningAverage(1, 60000 /* should be significantly longer than a typical transfer */, 0, Long.MAX_VALUE);

        
        
        // ARK stuff.

        parseARK(fs, true);
        
        arkFetcher = new ARKFetcher(this, node);
        
        // Now for the metadata.
        // The metadata sub-fieldset contains data about the node which is not part of the node reference.
        // It belongs to this node, not to the node being described.
        // Therefore, if we are parsing a remotely supplied ref, ignore it.
        
        long now = System.currentTimeMillis();
        if(fromLocal) {
        
        	SimpleFieldSet metadata = fs.subset("metadata");
        	
        	if(metadata != null) {
        		
        		// Don't be tolerant of nonexistant domains; this should be an IP address.
        		Peer p;
        		try {
        			String detectedUDPString = metadata.get("detected.udp");
        			p = null;
        			if(detectedUDPString != null)
        				p = new Peer(detectedUDPString, false);
        		} catch (UnknownHostException e) {
        			p = null;
        			Logger.error(this, "detected.udp = "+metadata.get("detected.udp")+" - "+e, e);
        		} catch (PeerParseException e) {
        			p = null;
        			Logger.error(this, "detected.udp = "+metadata.get("detected.udp")+" - "+e, e);
        		}
        		if(p != null)
        			detectedPeer = p;
            	String tempTimeLastReceivedPacketString = metadata.get("timeLastReceivedPacket");
            	if(tempTimeLastReceivedPacketString != null) {
            		long tempTimeLastReceivedPacket = Long.parseLong(tempTimeLastReceivedPacketString);
            		timeLastReceivedPacket = tempTimeLastReceivedPacket;
            	}
            	String tempTimeLastConnectedString = metadata.get("timeLastConnected");
            	if(tempTimeLastConnectedString != null) {
            		long tempTimeLastConnected = Long.parseLong(tempTimeLastConnectedString);
            		timeLastConnected = tempTimeLastConnected;
            	}
            	String tempTimeLastRoutableString = metadata.get("timeLastRoutable");
            	if(tempTimeLastRoutableString != null) {
            		long tempTimeLastRoutable = Long.parseLong(tempTimeLastRoutableString);
            		timeLastRoutable = tempTimeLastRoutable;
            	}
            	if(timeLastConnected < 1 && timeLastReceivedPacket > 1) {
            		timeLastConnected = timeLastReceivedPacket;
            	}
            	if(timeLastRoutable < 1 && timeLastReceivedPacket > 1) {
            		timeLastRoutable = timeLastReceivedPacket;
            	}
            	String tempPeerAddedTimeString = metadata.get("peerAddedTime");
            	if(tempPeerAddedTimeString != null) {
            		long tempPeerAddedTime = Long.parseLong(tempPeerAddedTimeString);
            		peerAddedTime = tempPeerAddedTime;
            	} else {
            		peerAddedTime = 1;
            	}
            	neverConnected = Fields.stringToBool(metadata.get("neverConnected"), false);
            	if((now - peerAddedTime) > (((long) 30)*24*60*60*1000)) {  // 30 days
            		peerAddedTime = 0;  // don't store anymore
            	}
            	if(!neverConnected) {
            		peerAddedTime = 0;  // don't store anymore
            	}
            	isDisabled = Fields.stringToBool(metadata.get("isDisabled"), false);
            	isListenOnly = Fields.stringToBool(metadata.get("isListenOnly"), false);
            	isBurstOnly = Fields.stringToBool(metadata.get("isBurstOnly"), false);
            	allowLocalAddresses = Fields.stringToBool(metadata.get("allowLocalAddresses"), false);
        	}
        } else {
            neverConnected = true;
            peerAddedTime = now;
        }
        // populate handshakeIPs so handshakes can start ASAP
        maybeUpdateHandshakeIPs(true);
        
        sendHandshakeTime = now;  // Be sure we're ready to handshake right away
    
		listeningHandshakeBurstCount = 0;
		listeningHandshakeBurstSize = Node.MIN_BURSTING_HANDSHAKE_BURST_SIZE
				+ node.random.nextInt(Node.RANDOMIZED_BURSTING_HANDSHAKE_BURST_SIZE);
		if(isBurstOnly) {
			Logger.minor(this, "First BurstOnly mode handshake in "+(sendHandshakeTime - now)+"ms for "+getName()+" (count: "+listeningHandshakeBurstCount+", size: "+listeningHandshakeBurstSize+")");
		}

        // status may have changed from PEER_NODE_STATUS_DISCONNECTED to PEER_NODE_STATUS_NEVER_CONNECTED
        setPeerNodeStatus(now);
    }

    private boolean parseARK(SimpleFieldSet fs, boolean onStartup) {
        USK ark = null;
        long arkNo = 0;
        try {
        	String arkNumber = fs.get("ark.number");
        	
        	if(arkNumber != null) {
        		arkNo = Long.parseLong(arkNumber) + (onStartup ? 0 : 1);
        		// this is the number of the ref we are parsing. we want the number of the next edition. on startup we want to fetch the old edition in case there's been a corruption.
        	}
        	
        	String arkPubKey = fs.get("ark.pubURI");
        	if(arkPubKey != null) {
        		FreenetURI uri = new FreenetURI(arkPubKey);
        		ClientSSK ssk = new ClientSSK(uri);
        		ark = new USK(ssk, arkNo);
        	}
        } catch (MalformedURLException e) {
        	Logger.error(this, "Couldn't parse ARK info for "+this+": "+e, e);
        } catch (NumberFormatException e) {
        	Logger.error(this, "Couldn't parse ARK info for "+this+": "+e, e);
        }

		synchronized(this) {
			if(ark != null) {
				if((myARK == null) || ((myARK != ark) && !myARK.equals(ark))) {
					myARK = ark;
					return true;
				}
			}
		}
        return false;
	}

    //FIXME: Huh wtf ?
	private void randomizeMaxTimeBetweenPacketSends() {
        int x = Node.KEEPALIVE_INTERVAL;
        x += node.random.nextInt(x);
    }

    /**
     * Get my low-level address
     */
    public synchronized Peer getDetectedPeer() {
        return detectedPeer;
    }

    public synchronized Peer getPeer(){
    	return detectedPeer;
    }
    
    /**
     * Returns an array with the advertised addresses and the detected one
     */
    protected synchronized Peer[] getHandshakeIPs(){
        return handshakeIPs;
    }
    
    private String handshakeIPsToString() {
        Peer[] localHandshakeIPs;
        synchronized(this) {
        	localHandshakeIPs = handshakeIPs;
        }
    	if(localHandshakeIPs == null)
    		return "null";
    	StringBuffer toOutputString = new StringBuffer(1024);
    	boolean needSep = false;
    	toOutputString.append("[ ");
        for(int i=0;i<localHandshakeIPs.length;i++) {
        	if(needSep)
        		toOutputString.append(", ");
        	if(localHandshakeIPs[i] == null) {
        		toOutputString.append("null");
    			needSep = true;
        		continue;
        	}
    		toOutputString.append("'");
        	// Actually do the DNS request for the member Peer of localHandshakeIPs
        	toOutputString.append(localHandshakeIPs[i].getAddress(false));
    		toOutputString.append("'");
    		needSep = true;
        }
    	toOutputString.append(" ]");
    	return toOutputString.toString();
    }

    /**
      * Do the maybeUpdateHandshakeIPs DNS requests, but only if ignoreHostnames is false
      * This method should only be called by maybeUpdateHandshakeIPs
      */
    private Peer[] updateHandshakeIPs(Peer[] localHandshakeIPs, boolean ignoreHostnames) {
        for(int i=0;i<localHandshakeIPs.length;i++) {
          if(ignoreHostnames) {
            // Don't do a DNS request on the first cycle through PeerNodes by DNSRequest
            // upon startup (I suspect the following won't do anything, but just in case)
            Logger.debug(this, "updateHandshakeIPs: calling getAddress(false) on Peer '"+localHandshakeIPs[i]+"' for PeerNode '"+getPeer()+"' named '"+getName()+"' ("+ignoreHostnames+")");
            localHandshakeIPs[i].getAddress(false);
          } else {
            // Actually do the DNS request for the member Peer of localHandshakeIPs
            Logger.debug(this, "updateHandshakeIPs: calling getHandshakeAddress() on Peer '"+localHandshakeIPs[i]+"' for PeerNode '"+getPeer()+"' named '"+getName()+"' ("+ignoreHostnames+")");
            localHandshakeIPs[i].getHandshakeAddress();
          }
        }
        return localHandshakeIPs;
    }

    /**
      * Do occasional DNS requests, but ignoreHostnames should be true
      * on PeerNode construction
      */
    public void maybeUpdateHandshakeIPs(boolean ignoreHostnames) {
    	long now = System.currentTimeMillis();
    	Peer localDetectedPeer = null;
    	synchronized(this) {
    		localDetectedPeer = detectedPeer;
	    	if((now - lastAttemptedHandshakeIPUpdateTime) < (5*60*1000)) {  // 5 minutes
	    		//Logger.minor(this, "Looked up recently (localDetectedPeer = "+localDetectedPeer + " : "+((localDetectedPeer == null) ? "" : localDetectedPeer.getAddress(false).toString()));
	    		return;  // 5 minutes FIXME
	    	}
			// We want to come back right away for DNS requesting if this is our first time through
			if(!ignoreHostnames) {
				lastAttemptedHandshakeIPUpdateTime = now;
			}
	    }
    	Logger.minor(this, "Updating handshake IPs for peer '"+getPeer()+"' named '"+getName()+"' ("+ignoreHostnames+")");
    	Peer[] localHandshakeIPs;
    	Peer[] myNominalPeer;
    	
    	// Don't synchronize while doing lookups which may take a long time!
    	synchronized(this) {
    		myNominalPeer = (Peer[]) nominalPeer.toArray(new Peer[nominalPeer.size()]);
    	}
    	
    	if(myNominalPeer.length == 0) {
    		if(localDetectedPeer == null) {
    			localHandshakeIPs = null;
        		synchronized(this) {
    				handshakeIPs = localHandshakeIPs;
    			}
    			Logger.minor(this, "1: maybeUpdateHandshakeIPs got a result of: "+handshakeIPsToString());
    			return;
    		}
    		localHandshakeIPs = new Peer[] { localDetectedPeer };
    		localHandshakeIPs = updateHandshakeIPs(localHandshakeIPs, ignoreHostnames);
        	synchronized(this) {
    			handshakeIPs = localHandshakeIPs;
    		}
    		Logger.minor(this, "2: maybeUpdateHandshakeIPs got a result of: "+handshakeIPsToString());
    		return;
    	}

    	// Hack for two nodes on the same IP that can't talk over inet for routing reasons
    	FreenetInetAddress localhost = node.fLocalhostAddress;
    	Peer[] nodePeers = node.getPrimaryIPAddress();
//    	FreenetInetAddress nodeAddr = node.getPrimaryIPAddress();
    	
    	Vector peers = null;
    	synchronized(this) {
    		peers = new Vector(nominalPeer);
    	}
    	
    	boolean addedLocalhost = false;
    	Peer detectedDuplicate = null;
    	for(int i=0;i<myNominalPeer.length;i++) {
    		Peer p = myNominalPeer[i];
    		if(p == null) continue;
    		if(localDetectedPeer != null) {
    			if((p != localDetectedPeer) && p.equals(localDetectedPeer)) {
    				// Equal but not the same object; need to update the copy.
    				detectedDuplicate = p;
    			}
    		}
    		FreenetInetAddress addr = p.getFreenetAddress();
    		if(addr.equals(localhost)) {
    			if(addedLocalhost) continue;
    			addedLocalhost = true;
    		}
    		for(int j=0;j<nodePeers.length;j++) {
    			if(nodePeers[j].getFreenetAddress().equals(addr)) {
        			if(!addedLocalhost)
        				peers.add(new Peer(localhost, p.getPort()));
    				addedLocalhost = true;
    			}
    		}
    		if(peers.contains(p)) continue;
    		peers.add(p);
    	}
    	
    	localHandshakeIPs = (Peer[]) peers.toArray(new Peer[peers.size()]);
    	localHandshakeIPs = updateHandshakeIPs(localHandshakeIPs, ignoreHostnames);
    	synchronized(this) {
    		handshakeIPs = localHandshakeIPs;
        	if((detectedDuplicate != null) && detectedDuplicate.equals(localDetectedPeer)) {
        		localDetectedPeer = detectedPeer = detectedDuplicate;
        	}
    	}
    	Logger.minor(this, "3: detectedPeer = "+localDetectedPeer+" ("+localDetectedPeer.getAddress(false)+")");
    	Logger.minor(this, "3: maybeUpdateHandshakeIPs got a result of: "+handshakeIPsToString());
    }
    
    /**
     * What is my current keyspace location?
     */
    public synchronized Location getLocation() {
        return currentLocation;
    }
    
    /**
     * Returns a unique node identifier (usefull to compare 2 pn)
     */
    public int getIdentityHash(){
    	return hashCode;
    }
    
    /**
     * Is this peer too old for us? (i.e. our lastGoodVersion is newer than it's version)
     * 
     */
    public synchronized boolean isVerifiedIncompatibleOlderVersion() {
        return verifiedIncompatibleOlderVersion;
    }
    
    /**
     * Is this peer too new for us? (i.e. our version is older than it's lastGoodVersion)
     * 
     */
    public synchronized boolean isVerifiedIncompatibleNewerVersion() {
        return verifiedIncompatibleNewerVersion;
    }
    
    /**
     * Is this peer currently connected?
     * 
     * Note possible deadlocks! PeerManager calls this, we call
     * PeerManager in e.g. verified.
     */
    public boolean isRoutable() {
        return isConnected() && isRoutingCompatible();
    }
    
    public boolean isRoutingCompatible(){
    	long now = System.currentTimeMillis();
    	synchronized(this) {
	    	if( isRoutable ) {
    			timeLastRoutable = now;
    			return true;
    		}
			return false;
    	}
    }
    public boolean isConnected(){
    	long now = System.currentTimeMillis();
    	synchronized(this) {
	    	if( isConnected ) {
    			timeLastConnected = now;
    			return true;
    		}
			return false;
    	}
    }

    /**
     * Send a message, off-thread, to this node.
     * @param msg The message to be sent.
     * @param cb The callback to be called when the packet has been sent, or null.
     * @param alreadyReportedBytes The number of bytes already reported to the throttle
     * relating to this packet (normally set when we have delayed a packet in order to
     * throttle it).
     */
    public void sendAsync(Message msg, AsyncMessageCallback cb, int alreadyReportedBytes, ByteCounter ctr) throws NotConnectedException {
        Logger.minor(this, "Sending async: "+msg+" : "+cb+" on "+this);
        if(!isConnected()) throw new NotConnectedException();
        MessageItem item = new MessageItem(msg, cb == null ? null : new AsyncMessageCallback[] {cb}, alreadyReportedBytes, ctr);
        long now = System.currentTimeMillis();
		reportBackoffStatus(now);
        synchronized(messagesToSendNow) {
            messagesToSendNow.addLast(item);
        }
        synchronized(node.ps) {
            node.ps.notifyAll();
        }
    }

    /**
     * @return The last time we received a packet.
     */
    public synchronized long lastReceivedPacketTime() {
        return timeLastReceivedPacket;
    }

    public synchronized long timeLastConnected() {
        return timeLastConnected;
    }

    public synchronized long timeLastRoutable() {
        return timeLastRoutable;
    }

    /**
     * @return The time this PeerNode was added to the node
     */
    public synchronized long getPeerAddedTime() {
        return peerAddedTime;
    }

    /**
     * Disconnected e.g. due to not receiving a packet for ages.
     */
    public void disconnected() {
        long now = System.currentTimeMillis();
        Logger.normal(this, "Disconnected "+this);
        node.usm.onDisconnect(this);
        node.peers.disconnected(this);
        synchronized(this) {
        	// Force renegotiation.
            isConnected = false;
            isRoutable = false;
            completedHandshake = false;
            setPeerNodeStatus(now);
            // Prevent sending packets to the node until that happens.
            if(currentTracker != null)
            	currentTracker.disconnected();
            if(previousTracker != null)
                previousTracker.disconnected();
            if(unverifiedTracker != null)
                unverifiedTracker.disconnected();
            // DO NOT clear trackers, so can still receive.
        }
        node.lm.lostOrRestartedNode(this);
        synchronized(this) {
			sendHandshakeTime = now;
	    }
    }

    public void forceDisconnect() {
    	Logger.error(this, "Forcing disconnect on "+this, new Exception("debug"));
    	disconnected();
    }
    
    /**
     * Grab all queued Message's.
     * @return Null if no messages are queued, or an array of
     * Message's.
     */
    public MessageItem[] grabQueuedMessageItems() {
        synchronized(messagesToSendNow) {
            if(messagesToSendNow.size() == 0) return null;
            MessageItem[] messages = new MessageItem[messagesToSendNow.size()];
            messages = (MessageItem[])messagesToSendNow.toArray(messages);
            messagesToSendNow.clear();
            return messages;
        }
    }
    
    public void requeueMessageItems(MessageItem[] messages, int offset, int length, boolean dontLog) {
        requeueMessageItems( messages, offset, length, dontLog, "" );
    }
    
    public void requeueMessageItems(MessageItem[] messages, int offset, int length, boolean dontLog, String reason) {
        // Will usually indicate serious problems
        if(!dontLog) {
            long now = System.currentTimeMillis();
            String rateLimitWrapper = "";
            boolean rateLimitLogging = false;
            if( messages.length > messageRequeueLogRateLimitThreshold ) {
              rateLimitWrapper = " (rate limited)";
              if(nextMessageRequeueLogTime <= now ) {
                nextMessageRequeueLogTime = now + messageRequeueLogRateLimitInterval;
              } else {
                rateLimitLogging = true;
              }
            }
            if(!rateLimitLogging) {
                String reasonWrapper = "";
                if( 0 <= reason.length()) {
                  reasonWrapper = " because of '"+reason+"'";
                }
                Logger.normal(this, "Requeueing "+messages.length+" messages"+reasonWrapper+" on "+this+rateLimitWrapper);
            }
        }
        synchronized(messagesToSendNow) {
            for(int i=offset;i<offset+length;i++)
                if(messages[i] != null)
                    messagesToSendNow.add(messages[i]);
        }
    }

    /**
     * @return The time at which we must send a packet, even if
     * it means it will only contains ack requests etc., or
     * Long.MAX_VALUE if we have no pending ack request/acks/etc.
     */
    public synchronized long getNextUrgentTime() {
        long t = Long.MAX_VALUE;
        KeyTracker kt = currentTracker;
        if(kt != null)
            t = Math.min(t, kt.getNextUrgentTime());
        kt = previousTracker;
        if(kt != null)
            t = Math.min(t, kt.getNextUrgentTime());
        return t;
    }

    /**
     * @return The time at which we last sent a packet.
     */
    public long lastSentPacketTime() {
        return timeLastSentPacket;
    }

    /**
     * @return True, if we are disconnected and it has been a
     * sufficient time period since we last sent a handshake
     * attempt.
     */
    public boolean shouldSendHandshake() {
        long now = System.currentTimeMillis();
        boolean tempShouldSendHandshake = false;
        synchronized(this) {
        	tempShouldSendHandshake = (!isConnected()) &&
				(!isDisabled) &&  // don't connect to disabled peers
				(!isListenOnly) &&  // don't send handshake requests to isListenOnly peers
                (handshakeIPs != null) &&
                (now > sendHandshakeTime);
		}
		if(tempShouldSendHandshake && (hasLiveHandshake(now))) {
			tempShouldSendHandshake = false;
		}
		if(tempShouldSendHandshake && isBurstOnly()) {
			synchronized(this) {
				isBursting = true;
			}
			setPeerNodeStatus(now);
		}
		return tempShouldSendHandshake;
    }
    
    /**
     * Does the node have a live handshake in progress?
     * @param now The current time.
     */
    public boolean hasLiveHandshake(long now) {
        DiffieHellmanContext c = null;
        synchronized(this) {
	        c = ctx;
		}
        if(c != null)
            Logger.minor(this, "Last used: "+(now - c.lastUsedTime()));
        return !((c == null) || (now - c.lastUsedTime() > Node.HANDSHAKE_TIMEOUT));
    }

    boolean firstHandshake = true;

    private void calcNextHandshake(boolean couldSendHandshake) {
        long now = System.currentTimeMillis();
        boolean fetchARKFlag = false;
        synchronized(this) {
			if(!isBurstOnly) {
				if(verifiedIncompatibleOlderVersion || verifiedIncompatibleNewerVersion) { 
					// Let them know we're here, but have no hope of connecting
					sendHandshakeTime = now + Node.MIN_TIME_BETWEEN_VERSION_SENDS
						+ node.random.nextInt(Node.RANDOMIZED_TIME_BETWEEN_VERSION_SENDS);
				} else if(invalidVersion() && !firstHandshake) {
					sendHandshakeTime = now + Node.MIN_TIME_BETWEEN_VERSION_PROBES
						+ node.random.nextInt(Node.RANDOMIZED_TIME_BETWEEN_VERSION_PROBES);
				} else {
					sendHandshakeTime = now + Node.MIN_TIME_BETWEEN_HANDSHAKE_SENDS
						+ node.random.nextInt(Node.RANDOMIZED_TIME_BETWEEN_HANDSHAKE_SENDS);
				}
				if(couldSendHandshake) {
					firstHandshake = false;
				} else {
					handshakeIPs = null;
				}
				handshakeCount++;
				fetchARKFlag = ((handshakeCount == MAX_HANDSHAKE_COUNT) && !(verifiedIncompatibleOlderVersion || verifiedIncompatibleNewerVersion));
			} else {
				listeningHandshakeBurstCount++;
				if(verifiedIncompatibleOlderVersion || verifiedIncompatibleNewerVersion) { 
					// Let them know we're here, but have no hope of connecting
					listeningHandshakeBurstCount = 0;
				} else if(listeningHandshakeBurstCount >= listeningHandshakeBurstSize) {
					listeningHandshakeBurstCount = 0;
					fetchARKFlag = true;
				}
				if(listeningHandshakeBurstCount == 0) {  // 0 only if we just reset it above
					sendHandshakeTime = now + Node.MIN_TIME_BETWEEN_BURSTING_HANDSHAKE_BURSTS
						+ node.random.nextInt(Node.RANDOMIZED_TIME_BETWEEN_BURSTING_HANDSHAKE_BURSTS);
					listeningHandshakeBurstSize = Node.MIN_BURSTING_HANDSHAKE_BURST_SIZE
							+ node.random.nextInt(Node.RANDOMIZED_BURSTING_HANDSHAKE_BURST_SIZE);
					isBursting = false;
				} else {
					sendHandshakeTime = now + Node.MIN_TIME_BETWEEN_HANDSHAKE_SENDS
						+ node.random.nextInt(Node.RANDOMIZED_TIME_BETWEEN_HANDSHAKE_SENDS);
				}
				if(!couldSendHandshake) {
					handshakeIPs = null;
				}
				Logger.minor(this, "Next BurstOnly mode handshake in "+(sendHandshakeTime - now)+"ms for "+getName()+" (count: "+listeningHandshakeBurstCount+", size: "+listeningHandshakeBurstSize+")", new Exception("double-called debug"));
			}
        }
		setPeerNodeStatus(now);  // Because of isBursting being set above and it can't hurt others
        // Don't fetch ARKs for peers we have verified (through handshake) to be incompatible with us
        if(fetchARKFlag) {
			long arkFetcherStartTime1 = System.currentTimeMillis();
			arkFetcher.queue();
			long arkFetcherStartTime2 = System.currentTimeMillis();
			if((arkFetcherStartTime2 - arkFetcherStartTime1) > 500) {
				Logger.normal(this, "arkFetcherStartTime2 is more than half a second after arkFetcherStartTime1 ("+(arkFetcherStartTime2 - arkFetcherStartTime1)+") working on "+getName());
			}
        }
    }
    
    /**
     * Call this method when a handshake request has been
     * sent.
     */
    public void sentHandshake() {
        Logger.minor(this, "sentHandshake(): "+this);
        calcNextHandshake(true);
    }
    
    /**
     * Call this method when a handshake request could not be sent (i.e. no IP address available)
     * sent.
     */
    public void couldNotSendHandshake() {
        Logger.minor(this, "couldNotSendHandshake(): "+this);
        calcNextHandshake(false);
    }

    /**
     * @return The maximum time between received packets.
     */
    public int maxTimeBetweenReceivedPackets() {
    	return Node.MAX_PEER_INACTIVITY;
    }
    
    /**
     * Low-level ping this node.
     * @return True if we received a reply inside 2000ms.
     * (If we have heavy packet loss, it can take that long to resend).
     */
    public boolean ping(int pingID) throws NotConnectedException {
        Message ping = DMT.createFNPPing(pingID);
        node.usm.send(this, ping, null);
        Message msg;
        try {
            msg = node.usm.waitFor(MessageFilter.create().setTimeout(2000).setType(DMT.FNPPong).setField(DMT.PING_SEQNO, pingID), null);
        } catch (DisconnectedException e) {
            throw new NotConnectedException("Disconnected while waiting for pong");
        }
        return msg != null;
    }
    
    /**
     * Decrement the HTL (or not), in accordance with our 
     * probabilistic HTL rules.
     * @param htl The old HTL.
     * @return The new HTL.
     */
    public short decrementHTL(short htl) {
        if(htl > Node.MAX_HTL) htl = Node.MAX_HTL;
        if(htl <= 0) htl = 1;
        if(htl == Node.MAX_HTL) {
            if(decrementHTLAtMaximum) htl--;
            return htl;
        }
        if(htl == 1) {
            if(decrementHTLAtMinimum) htl--;
            return htl;
        }
        htl--;
        return htl;
    }

    /**
     * Send a message, right now, on this thread, to this node.
     */
    public void send(Message req, ByteCounter ctr) throws NotConnectedException {
    	synchronized(this) {
            if(!isConnected()) {
                Logger.error(this, "Tried to send "+req+" but not connected to "+this, new Exception("debug"));
                return;
            }
		}
        node.usm.send(this, req, ctr);
    }

    /**
     * Update the Location to a new value.
     */
    public void updateLocation(double newLoc) {
		synchronized(this) {
			currentLocation.setValue(newLoc);
		}
        node.peers.writePeers();
    }

    /**
     * Should we reject a swap request?
     */
    public boolean shouldRejectSwapRequest() {
        long now = System.currentTimeMillis();
        synchronized(this) {
			if(timeLastReceivedSwapRequest > 0) {
				long timeSinceLastTime = now - timeLastReceivedSwapRequest;
				swapRequestsInterval.report(timeSinceLastTime);
				double averageInterval = swapRequestsInterval.currentValue();
				if(averageInterval < Node.MIN_INTERVAL_BETWEEN_INCOMING_SWAP_REQUESTS) {
					double p = 
						(Node.MIN_INTERVAL_BETWEEN_INCOMING_SWAP_REQUESTS - averageInterval) /
						Node.MIN_INTERVAL_BETWEEN_INCOMING_SWAP_REQUESTS;
					return node.random.nextDouble() < p;
				} else return false;
					
			}
			timeLastReceivedSwapRequest = now;
		}
        return false;
    }

    /**
     * IP on the other side appears to have changed...
     * @param newPeer The new address of the peer.
     */
    public void changedIP(Peer newPeer) {
    	setDetectedPeer(newPeer);
    }

    private synchronized void setDetectedPeer(Peer newPeer) {
    	// Only clear lastAttemptedHandshakeIPUpdateTime if we have a new IP.
    	// Also, we need to call .equals() to propagate any DNS lookups that have been done if the two have the same domain.
    	if((newPeer != null) && ((detectedPeer == null) || !detectedPeer.equals(newPeer))) {
    		this.detectedPeer=newPeer;
    		this.lastAttemptedHandshakeIPUpdateTime = 0;
    	}
	}

	/**
     * @return The current primary KeyTracker, or null if we
     * don't have one.
     */
    public synchronized KeyTracker getCurrentKeyTracker() {
        return currentTracker;
    }

    /**
     * @return The previous primary KeyTracker, or null if we
     * don't have one.
     */
    public synchronized KeyTracker getPreviousKeyTracker() {
        return previousTracker;
    }
    
    /**
     * @return The unverified KeyTracker, if any, or null if we
     * don't have one. The caller MUST call verified(KT) if a
     * decrypt succeeds with this KT.
     */
    public synchronized KeyTracker getUnverifiedKeyTracker() {
        return unverifiedTracker;
    }

    /**
     * @return short version of toString()
     */
    public synchronized String shortToString() {
        return super.toString()+"@"+detectedPeer+"@"+HexUtil.bytesToHex(identity);
    }

    public String toString() {
        // FIXME?
        return shortToString();
    }
    
    /**
     * Update timeLastReceivedPacket
     * @throws NotConnectedException 
     * @param dontLog If true, don't log an error or throw an exception if we are not connected. This
     * can be used in handshaking when the connection hasn't been verified yet.
     */
	void receivedPacket(boolean dontLog) throws NotConnectedException {
		synchronized(this) {
			if(!isConnected() && !dontLog) {
				if((unverifiedTracker == null) && (currentTracker == null)) {
					Logger.error(this, "Received packet while disconnected!: "+this, new Exception("error"));
					throw new NotConnectedException();
				} else {
					Logger.minor(this, "Received packet while disconnected on "+this+" - recently disconnected() ?");
				}
			}
		}
    	long now = System.currentTimeMillis();
		synchronized(this) {
			timeLastReceivedPacket = now;
		}
	}

    /**
     * Update timeLastSentPacket
     */
    public void sentPacket() {
        timeLastSentPacket = System.currentTimeMillis();
        randomizeMaxTimeBetweenPacketSends();
    }

    public synchronized DiffieHellmanContext getDHContext() {
        return ctx;
    }

    public synchronized void setDHContext(DiffieHellmanContext ctx2) {
        this.ctx = ctx2;
        Logger.minor(this, "setDHContext("+ctx2+") on "+this);
    }

    /**
     * Called when we have completed a handshake, and have a new session key.
     * Creates a new tracker and demotes the old one. Deletes the old one if
     * the bootID isn't recognized.
     * @param thisBootID The boot ID of the peer we have just connected to
     * @param data Byte array from which to read the new noderef
     * @param offset Offset to start reading at
     * @param length Number of bytes to read
     * @param encKey
     * @param replyTo
     * @return True unless we rejected the handshake, or it failed to parse.
     */
    public boolean completedHandshake(long thisBootID, byte[] data, int offset, int length, BlockCipher encCipher, byte[] encKey, Peer replyTo, boolean unverified) {
    	long now = System.currentTimeMillis();
    	arkFetcher.stop();
    	synchronized(this) {
    		completedHandshake = true;
    		handshakeCount = 0;
        	bogusNoderef = false;
        }
		try {
			// First, the new noderef
			processNewNoderef(data, offset, length);
		} catch (FSParseException e1) {
			synchronized(this) {
				bogusNoderef = true;
			}
			Logger.error(this, "Failed to parse new noderef for "+this+": "+e1, e1);
			// Treat as invalid version
		}
    	synchronized(this) {
        	isRoutable = true;
        }
		if(reverseInvalidVersion()) {
			try {
				node.setNewestPeerLastGoodVersion(Version.getArbitraryBuildNumber(getLastGoodVersion()));
			} catch (NumberFormatException e) {
				// ignore
			}
			Logger.normal(this, "Not connecting to "+this+" - reverse invalid version "+Version.getVersionString()+" for peer's lastGoodversion: "+getLastGoodVersion());
	    	synchronized(this) {
				verifiedIncompatibleNewerVersion = true;
				isRoutable = false;
			}
			node.peers.disconnected(this);
		} else {
	    	synchronized(this) {
				verifiedIncompatibleNewerVersion = false;
			}
		}
		if(invalidVersion()) {
			Logger.normal(this, "Not connecting to "+this+" - invalid version "+getVersion());
	    	synchronized(this) {
				verifiedIncompatibleOlderVersion = true;
				isRoutable = false;
			}
			node.peers.disconnected(this);
		} else {
	    	synchronized(this) {
				verifiedIncompatibleOlderVersion = false;
			}
		}
    	synchronized(this) {
			isConnected = true;
		}
		setPeerNodeStatus(now);
		KeyTracker newTracker = new KeyTracker(this, encCipher, encKey);
		changedIP(replyTo);
		boolean bootIDChanged = false;
    	synchronized(this) {
			bootIDChanged = (thisBootID != this.bootID);
		}
		if(bootIDChanged) {
	    	synchronized(this) {
				connectedTime = now;
				Logger.minor(this, "Changed boot ID from "+bootID+" to "+thisBootID+" for "+getPeer());
			}
			boolean previousTrackerIsNull = true;
			synchronized(this) {
				previousTrackerIsNull = (previousTracker == null);
			}
			if(!previousTrackerIsNull) {
				KeyTracker old = null;
				synchronized(this) {
					old = previousTracker;
					previousTracker = null;
				}
				old.completelyDeprecated(newTracker);
			}
			synchronized(this) {
				previousTracker = null;
			}
			boolean currentTrackerIsNull = true;
			synchronized(this) {
				currentTrackerIsNull = (currentTracker == null);
			}
			if(!currentTrackerIsNull) {
				KeyTracker old = null;
				synchronized(this) {
					old = currentTracker;
					currentTracker = null;
				}
				old.completelyDeprecated(newTracker);
			}
			synchronized(this) {
				this.bootID = thisBootID;
			}
			node.lm.lostOrRestartedNode(this);
		} // else it's a rekey
		
		if(unverified) {
			synchronized(this) {
				unverifiedTracker = newTracker;
				ctx = null;
			}
			Logger.minor(this, "sentHandshake() being called for unverifiedTracker: "+getPeer());
			sentHandshake();
		} else {
			synchronized(this) {
				previousTracker = currentTracker;
				currentTracker = newTracker;
				unverifiedTracker = null;
			}
			boolean previousTrackerIsNull = true;
			synchronized(this) {
				previousTrackerIsNull = (previousTracker == null);
			}
			if(!previousTrackerIsNull) {
				KeyTracker localPreviousTracker = null;
				synchronized(this) {
					localPreviousTracker = previousTracker;
				}
				localPreviousTracker.deprecated();
			}
			synchronized(this) {
				neverConnected = false;
				peerAddedTime = 0;  // don't store anymore
			}
			setPeerNodeStatus(now);
			synchronized(this) {
				ctx = null;
			}
		}
		if(!isConnected())
			node.peers.disconnected(this);
		synchronized(this) {
			Logger.normal(this, "Completed handshake with "+this+" on "+replyTo+" - current: "+currentTracker+" old: "+previousTracker+" unverified: "+unverifiedTracker+" bootID: "+thisBootID+" getName(): "+getName());
		}
		try {
			receivedPacket(unverified);
		} catch (NotConnectedException e) {
			Logger.error(this, "Disconnected in completedHandshake with "+this);
			return true; // i suppose
		}
		if(isConnected()) {
			node.peers.addConnectedPeer(this);
		}
		synchronized(this) {
			sentInitialMessages = false;
		}
		return true;
    }

    boolean sentInitialMessages;
    
    void maybeSendInitialMessages() {
        synchronized(this) {
            if(sentInitialMessages) return;
            if(currentTracker != null)
                sentInitialMessages = true;
            else return;
            if(unverifiedTracker != null) return;
        }
       
       sendInitialMessages();
    }
    
    /**
     * Send any high level messages that need to be sent on connect.
     */
    private void sendInitialMessages() {
        Message locMsg = DMT.createFNPLocChangeNotification(node.lm.loc.getValue());
        Message ipMsg = DMT.createFNPDetectedIPAddress(getDetectedPeer());
        
        try {
        	if(isRoutable())
        		 sendAsync(locMsg, null, 0, null);
            sendAsync(ipMsg, null, 0, null);
        } catch (NotConnectedException e) {
            Logger.error(this, "Completed handshake with "+getPeer()+" but disconnected!!!", new Exception("error"));
        }
    }

    /**
     * Called when a packet is successfully decrypted on a given
     * KeyTracker for this node. Will promote the unverifiedTracker
     * if necessary.
     */
    public void verified(KeyTracker tracker) {
      long now = System.currentTimeMillis();
    	synchronized(this) {
        if(tracker == unverifiedTracker) {
            Logger.minor(this, "Promoting unverified tracker "+tracker+" for "+getPeer());
            if(previousTracker != null) {
                previousTracker.completelyDeprecated(tracker);
            }
            previousTracker = currentTracker;
            if(previousTracker != null)
                previousTracker.deprecated();
            currentTracker = unverifiedTracker;
            unverifiedTracker = null;
            isConnected = true;
            neverConnected = false;
            peerAddedTime = 0;  // don't store anymore
            setPeerNodeStatus(now);
            ctx = null;
            maybeSendInitialMessages();
        } else return;
    	}
        node.peers.addConnectedPeer(this);
    }
    
    private synchronized boolean invalidVersion() {
        return bogusNoderef || (!Version.checkGoodVersion(version));
    }
    
    private synchronized boolean reverseInvalidVersion() {
        return bogusNoderef || (!Version.checkArbitraryGoodVersion(Version.getVersionString(),lastGoodVersion));
    }
    
    public boolean publicInvalidVersion() {
        return !Version.checkGoodVersion(getVersion());
    }
    
    public synchronized boolean publicReverseInvalidVersion() {
        return !Version.checkArbitraryGoodVersion(Version.getVersionString(),lastGoodVersion);
    }

    /**
     * Process a new nodereference, in compressed form.
     * The identity must not change, or we throw.
     */
    private void processNewNoderef(byte[] data, int offset, int length) throws FSParseException {
        if(length == 0) throw new FSParseException("Too short");
        // Firstly, is it compressed?
        if(data[offset] == 1) {
            // Gzipped
            Inflater i = new Inflater();
            i.setInput(data, offset+1, length-1);
            byte[] output = new byte[4096];
            int outputPointer = 1;
            while(true) {
                try {
                    int x = i.inflate(output, outputPointer, output.length-outputPointer);
                    if(x == output.length-outputPointer) {
                        // More to decompress!
                        byte[] newOutput = new byte[output.length*2];
                        System.arraycopy(output, 0, newOutput, 0, output.length);
                        continue;
                    } else {
                        // Finished
                        data = output;
                        offset = 0;
                        length = outputPointer + x;
                        break;
                    }
                } catch (DataFormatException e) {
                    throw new FSParseException("Invalid compressed data");
                }
            }
        }
        Logger.minor(this, "Reference: "+new String(data, offset, length)+"("+length+")");
        // Now decode it
        ByteArrayInputStream bais = new ByteArrayInputStream(data, offset+1, length-1);
        InputStreamReader isr;
        try {
            isr = new InputStreamReader(bais, "UTF-8");
        } catch (UnsupportedEncodingException e1) {
            throw new Error(e1);
        }
        BufferedReader br = new BufferedReader(isr);
        SimpleFieldSet fs;
        try {
            fs = new SimpleFieldSet(br, false);
        } catch (IOException e) {
            Logger.error(this, "Impossible: e", e);
            return;
        }
        processNewNoderef(fs, false);
    }

    /**
     * Process a new nodereference, as a SimpleFieldSet.
     */
    private void processNewNoderef(SimpleFieldSet fs, boolean forARK) throws FSParseException {
        Logger.minor(this, "Parsing: \n"+fs);
        boolean changedAnything = innerProcessNewNoderef(fs, forARK);
        if(changedAnything) node.peers.writePeers();
    }

    /** The synchronized part of processNewNoderef 
     * @throws FSParseException */
    private synchronized boolean innerProcessNewNoderef(SimpleFieldSet fs, boolean forARK) throws FSParseException {
        boolean changedAnything = false;
        String identityString = fs.get("identity");
        try {
            boolean base64 = Fields.stringToBool(fs.get("base64"), false);
            byte[] newIdentity = base64 ? Base64.decode(identityString) :
            	HexUtil.hexToBytes(identityString);
            if(!Arrays.equals(newIdentity, identity))
                throw new FSParseException("Identity changed!!");
        } catch (NumberFormatException e) {
            throw new FSParseException(e);
        } catch (IllegalBase64Exception e) {
            throw new FSParseException(e);
		}
        String newVersion = fs.get("version");
        if(newVersion == null) {
        	// Version may be ommitted for an ARK.
        	if(!forARK)
        		throw new FSParseException("No version");
        } else {
        	if(!newVersion.equals(version))
        		changedAnything = true;
        	version = newVersion;
        	Version.seenVersion(newVersion);
        }
        String locationString = fs.get("location");
        if(locationString == null) {
        	// Location WILL be ommitted for an ARK.
        	if(!forARK)
        		throw new FSParseException("No location");
        } else {
        	Location loc = new Location(locationString);
        	if(!loc.equals(currentLocation)) changedAnything = true;
        	currentLocation = loc;
        }

        Vector oldNominalPeer = nominalPeer;
        
        if(nominalPeer==null)
        	nominalPeer=new Vector();
        nominalPeer.removeAllElements();
        
        lastGoodVersion = fs.get("lastGoodVersion");
        
        Peer[] oldPeers = (Peer[]) nominalPeer.toArray(new Peer[nominalPeer.size()]);
        
        try{
        	String physical[]=fs.getAll("physical.udp");
        	if(physical==null){
        		Peer p = new Peer(fs.get("physical.udp"), true);
        		nominalPeer.addElement(p);
        	}else{
	    		for(int i=0;i<physical.length;i++){		
					Peer p = new Peer(physical[i], true);
				    if(!nominalPeer.contains(p)) {
				    	if(oldNominalPeer.contains(p)) {
				    		// Do nothing
				    		// .contains() will .equals() on each, and equals() will propagate the looked-up IP if necessary.
				    		// This is obviously O(n^2), but it doesn't matter, there will be very few peers.
				    	}
				    	nominalPeer.addElement(p);
				    }
	    		}
        	}
        	this.lastAttemptedHandshakeIPUpdateTime = 0;
        } catch (Exception e1) {
                throw new FSParseException(e1);
        }
        
        if(!Arrays.equals(oldPeers, nominalPeer.toArray(new Peer[nominalPeer.size()])))
        	changedAnything = true;
        
        // DO NOT change detectedPeer !!!
        // The given physical.udp may be WRONG!!!
        
        String name = fs.get("myName");
        if(name == null) throw new FSParseException("No name");
        // In future, ARKs may support automatic transition when the ARK key is changed.
        // So parse it anyway. If it fails, no big loss; it won't even log an error.
        
        Logger.minor(this, "Parsed successfully; changedAnything = "+changedAnything);
        
        if(parseARK(fs, false))
        	changedAnything = true;
        if(!name.equals(myName)) changedAnything = true;
        myName = name;
        
		return changedAnything;
	}

	/**
     * Send a payload-less packet on either key if necessary.
     * @throws PacketSequenceException If there is an error sending the packet
     * caused by a sequence inconsistency. 
     */
    public void sendAnyUrgentNotifications() throws PacketSequenceException {
        Logger.minor(this, "sendAnyUrgentNotifications");
        long now = System.currentTimeMillis();
        KeyTracker cur, prev;
        synchronized(this) {
            cur = currentTracker;
            prev = previousTracker;
        }
        KeyTracker tracker = cur;
        if(tracker != null) {
            long t = tracker.getNextUrgentTime();
            if(t < now) {
                try {
                    node.packetMangler.processOutgoing(null, 0, 0, tracker, 0);
                } catch (NotConnectedException e) {
                    // Ignore
                } catch (KeyChangedException e) {
                    // Ignore
				} catch (WouldBlockException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
            }
        }
        tracker = prev;
        if(tracker != null) {
            if(tracker.isDeprecated()) return;
            long t = tracker.getNextUrgentTime();
            if(t < now) {
                try {
                    node.packetMangler.processOutgoing(null, 0, 0, tracker, 0);
                } catch (NotConnectedException e) {
                    // Ignore
                } catch (KeyChangedException e) {
                    // Ignore
				} catch (WouldBlockException e) {
					Logger.error(this, "Impossible: "+e, e);
				}
            }
        }
    }

    public String getStatus() {
        return getPeerNodeStatusString() + " " + getPeer()+" "+getName()+" "+getLocation().getValue()+" "+getVersion()+" backoff: "+getRoutingBackoffLength()+" ("+(Math.max(getRoutingBackedOffUntil() - System.currentTimeMillis(),0))+")";
    }

    public String getTMCIPeerInfo() {
		long now = System.currentTimeMillis();
        int idle = -1;
        synchronized(this) {
        	idle = (int) ((now - timeLastReceivedPacket) / 1000);
        }
        if((getPeerNodeStatus() == Node.PEER_NODE_STATUS_NEVER_CONNECTED) && (getPeerAddedTime() > 1))
            idle = (int) ((now - getPeerAddedTime()) / 1000);
        return getName()+"\t"+getPeer()+"\t"+getIdentityString()+"\t"+getLocation().getValue()+"\t"+getPeerNodeStatusString()+"\t"+idle;
    }
    
    public String getFreevizOutput() {
    	return getStatus()+"|"+ Base64.encode(identity);
    }
	
	public synchronized String getVersion(){
		return version;
	}
	
	private synchronized String getLastGoodVersion(){
		return lastGoodVersion;
	}
	
	public String getSimpleVersion(){
		return String.valueOf(Version.getArbitraryBuildNumber(getVersion()));
	}

    /**
     * Write the peer's noderef to disk
     */
    public void write(Writer w) throws IOException {
        SimpleFieldSet fs = exportFieldSet();
        SimpleFieldSet meta = exportMetadataFieldSet();
        if(!meta.isEmpty())
        	fs.put("metadata", meta);
        fs.writeTo(w);
    }

    /**
     * Export metadata about the node as a SimpleFieldSet
     */
    public SimpleFieldSet exportMetadataFieldSet() {
    	SimpleFieldSet fs = new SimpleFieldSet(true);
    	if(getDetectedPeer() != null)
    		fs.put("detected.udp", getDetectedPeer().toString());
    	if(lastReceivedPacketTime() > 0)
    		fs.put("timeLastReceivedPacket", Long.toString(lastReceivedPacketTime()));
    	if(timeLastConnected() > 0)
    		fs.put("timeLastConnected", Long.toString(timeLastConnected()));
    	if(timeLastRoutable() > 0)
    		fs.put("timeLastRoutable", Long.toString(timeLastRoutable()));
    	if(getPeerAddedTime() > 0)
    		fs.put("peerAddedTime", Long.toString(getPeerAddedTime()));
    	if(neverConnected())
    		fs.put("neverConnected", "true");
    	if(isDisabled())
    		fs.put("isDisabled", "true");
    	if(isListenOnly())
    		fs.put("isListenOnly", "true");
    	if(isBurstOnly())
    		fs.put("isBurstOnly", "true");
    	if(allowLocalAddresses())
    		fs.put("allowLocalAddresses", "true");
    	return fs;
	}

    /**
     * Export volatile data about the node as a SimpleFieldSet
     */
    public SimpleFieldSet exportVolatileFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		long now = System.currentTimeMillis();
		fs.put("averagePingTime", Double.toString(averagePingTime()));
		long idle = now - lastReceivedPacketTime();
		if(idle > (60 * 1000)) {  // 1 minute
			fs.put("idle", Long.toString(idle));
		}
		fs.put("lastRoutingBackoffReason", getLastBackoffReason());
		long tempPeerAddedTime = getPeerAddedTime();
		if(tempPeerAddedTime > 1) {
			fs.put("peerAddedTime", Long.toString(tempPeerAddedTime));
		}
		synchronized(this) {
			fs.put("routingBackoffPercent", Double.toString(backedOffPercent.currentValue() * 100));
		}
		fs.put("routingBackoff", Long.toString((Math.max(getRoutingBackedOffUntil() - now, 0))));
		fs.put("routingBackoffLength", Integer.toString(getRoutingBackoffLength()));
		fs.put("status", getPeerNodeStatusString());
		return fs;
	}

	/**
     * Export the peer's noderef as a SimpleFieldSet
     */
    public SimpleFieldSet exportFieldSet() {
        SimpleFieldSet fs = new SimpleFieldSet(true);
        if(getLastGoodVersion() != null)
        	fs.put("lastGoodVersion", getLastGoodVersion());
		synchronized(this) {
			for(int i=0;i<nominalPeer.size();i++) {
				fs.put("physical.udp", nominalPeer.get(i).toString());
			}
		}
        fs.put("base64", "true");
        fs.put("identity", getIdentityString());
        fs.put("location", Double.toString(getLocation().getValue()));
		synchronized(this) {
	        fs.put("testnet", Boolean.toString(testnetEnabled));
		}
        fs.put("version", getVersion());
        fs.put("myName", getName());
		synchronized(this) {
			if(myARK != null) {
				// Decrement it because we keep the number we would like to fetch, not the last one fetched.
				fs.put("ark.number", Long.toString(myARK.suggestedEdition - 1));
				fs.put("ark.pubURI", myARK.getBaseSSK().toString(false));
			}
        }
        return fs;
    }

    /**
     * @return The time at which we last connected (or reconnected).
     */
    public synchronized long timeLastConnectionCompleted() {
        return connectedTime;
    }

    /**
     * Requeue ResendPacketItem[]s if they are not sent.
     * @param resendItems
     */
    public void requeueResendItems(ResendPacketItem[] resendItems) {
    	KeyTracker cur, prev, unv;
    	synchronized(this) {
    		cur = currentTracker;
    		prev = previousTracker;
    		unv = unverifiedTracker;
    	}
        for(int i=0;i<resendItems.length;i++) {
            ResendPacketItem item = resendItems[i];
            if(item.pn != this)
                throw new IllegalArgumentException("item.pn != this!");
            KeyTracker kt = cur;
            if((kt != null) && (item.kt == kt)) {
                kt.resendPacket(item.packetNumber);
                continue;
            }
            kt = prev;
            if((kt != null) && (item.kt == kt)) {
                kt.resendPacket(item.packetNumber);
                continue;
            }
            kt = unv;
            if((kt != null) && (item.kt == kt)) {
                kt.resendPacket(item.packetNumber);
                continue;
            }
            // Doesn't match any of these, need to resend the data
            kt = cur == null ? unv : cur;
            if(kt == null) {
                Logger.error(this, "No tracker to resend packet "+item.packetNumber+" on");
                continue;
            }
            MessageItem mi = new MessageItem(item.buf, item.callbacks, true, 0, null);
            requeueMessageItems(new MessageItem[] {mi}, 0, 1, true);
        }
    }
    
    public boolean equals(Object o) {
        if(o == this) return true;
        if(o instanceof PeerNode) {
            PeerNode pn = (PeerNode) o;
            return Arrays.equals(pn.identity, identity);
        } else return false;
    }
    
    public int hashCode() {
        return hashCode;
    }

	private final Object routingBackoffSync = new Object();
	
	public boolean isRoutingBackedOff() {
		long now = System.currentTimeMillis();
		synchronized(routingBackoffSync) {
			if(now < routingBackedOffUntil) {
				Logger.minor(this, "Routing is backed off");
				return true;
			} else return false;
		}
	}
	
	long routingBackedOffUntil = -1;
	/** Initial nominal routing backoff length */
	static final int INITIAL_ROUTING_BACKOFF_LENGTH = 1000;  // 1 second
	/** How much to multiply by during fast routing backoff */
	static final int BACKOFF_MULTIPLIER = 2;
	/** Maximum upper limit to routing backoff slow or fast */
	static final int MAX_ROUTING_BACKOFF_LENGTH = 3*60*60*1000;  // 3 hours
	/** Current nominal routing backoff length */
	int routingBackoffLength = INITIAL_ROUTING_BACKOFF_LENGTH;
	/** Last backoff reason */
	String lastRoutingBackoffReason;
	/** Previous backoff reason (used by setPeerNodeStatus)*/
	String previousRoutingBackoffReason;
	/* percent of time this peer is backedoff */
	public RunningAverage backedOffPercent = new TimeDecayingRunningAverage(0.0, 180000, 0.0, 1.0);
	/* time of last sample */
	private long lastSampleTime = Long.MAX_VALUE;
    
	/**
	 * Got a local RejectedOverload.
	 * Back off this node for a while.
	 */
	public void localRejectedOverload() {
	  localRejectedOverload("");
	}

	/** 
	 * Track the percentage of time a peer spends backed off
	 */
	private void reportBackoffStatus(long now) {
		long localRoutingBackedOffUntil = -1;
		synchronized(routingBackoffSync) {
			localRoutingBackedOffUntil = routingBackedOffUntil;
		}
		synchronized(this) {
			if(now > lastSampleTime) {
				if (now > localRoutingBackedOffUntil) {
					if (lastSampleTime > localRoutingBackedOffUntil) {
						backedOffPercent.report(0.0);
					} else {
						backedOffPercent.report((double)(localRoutingBackedOffUntil - lastSampleTime)/(double)(now - lastSampleTime));
					}
				} else {
					backedOffPercent.report(1.0);
				}
			}
			lastSampleTime = now;
		}
	}
	
	/**
	 * Got a local RejectedOverload.
	 * Back off this node for a while.
	 */
	public void localRejectedOverload(String reason) {
		Logger.minor(this, "Local rejected overload on "+this);
		long now = System.currentTimeMillis();
		Peer peer = getPeer();
		reportBackoffStatus(now);
		synchronized(routingBackoffSync) {
			// Don't back off any further if we are already backed off
			if(now > routingBackedOffUntil) {
				routingBackoffLength = routingBackoffLength * BACKOFF_MULTIPLIER;
				if(routingBackoffLength > MAX_ROUTING_BACKOFF_LENGTH)
					routingBackoffLength = MAX_ROUTING_BACKOFF_LENGTH;
				int x = node.random.nextInt(routingBackoffLength);
				routingBackedOffUntil = now + x;
				setLastBackoffReason( reason );
				setPeerNodeStatus(now);
				String reasonWrapper = "";
				if( 0 <= reason.length()) {
					reasonWrapper = " because of '"+reason+"'";
				}

				Logger.minor(this, "Backing off"+reasonWrapper+": routingBackoffLength="+routingBackoffLength+", until "+x+"ms on "+peer);
			} else {
				Logger.minor(this, "Ignoring localRejectedOverload: "+(routingBackedOffUntil-now)+"ms remaining on routing backoff on "+peer);
			}
		}
	}
	
	/**
	 * Didn't get RejectedOverload.
	 * Reset routing backoff.
	 */
	public void successNotOverload() {
		Logger.minor(this, "Success not overload on "+this);
		Peer peer = getPeer();
		long now = System.currentTimeMillis();
		reportBackoffStatus(now);
		synchronized(routingBackoffSync) {
			// Don't un-backoff if still backed off
			if(now > routingBackedOffUntil) {
				routingBackoffLength = INITIAL_ROUTING_BACKOFF_LENGTH;
				Logger.minor(this, "Resetting routing backoff on "+peer);
				setPeerNodeStatus(now);
			} else {
				Logger.minor(this, "Ignoring successNotOverload: "+(routingBackedOffUntil-now)+"ms remaining on routing backoff on "+peer);
			}
		}
	}

	Object pingSync = new Object();
	// Relatively few as we only get one every 200ms*#nodes
	// We want to get reasonably early feedback if it's dropping all of them...
	final static int MAX_PINGS = 5;
	final LRUHashtable pingsSentTimes = new LRUHashtable();
	long pingNumber;
	final RunningAverage pingAverage;
	
	public void sendPing() {
		long pingNo;
		long now = System.currentTimeMillis();
		Long lPingNo;
		synchronized(pingSync) {
			pingNo = pingNumber++;
			lPingNo = new Long(pingNo);
			Long lnow = new Long(now);
			pingsSentTimes.push(lPingNo, lnow);
			Logger.minor(this, "Pushed "+lPingNo+" "+lnow);
			while(pingsSentTimes.size() > MAX_PINGS) {
				Long l = (Long) pingsSentTimes.popValue();
				Logger.minor(this, "pingsSentTimes.size()="+pingsSentTimes.size()+", l="+l);
				long tStarted = l.longValue();
				pingAverage.report(now - tStarted);
				Logger.minor(this, "Reporting dumped ping time to "+this+" : "+(now - tStarted));
			}
		}
		Message msg = DMT.createFNPLinkPing(pingNo);
		try {
			sendAsync(msg, null, 0, null);
		} catch (NotConnectedException e) {
			synchronized(pingSync) {
				pingsSentTimes.removeKey(lPingNo);
			}
		}
	}

	public void receivedLinkPong(long id) {
		Long lid = new Long(id);
		long startTime;
		long now = System.currentTimeMillis();
		synchronized(pingSync) {
			Long s = (Long) pingsSentTimes.get(lid);
			if(s == null) {
				Logger.normal(this, "Dropping ping "+id+" on "+this);
				return;
			}
			startTime = s.longValue();
			pingsSentTimes.removeKey(lid);
			pingAverage.report(now - startTime);
			Logger.minor(this, "Reporting ping time to "+this+" : "+(now - startTime));
		}
		
		if(shouldDisconnectNow()){
				invalidate();
				setPeerNodeStatus(now);
		}
	}

	public double averagePingTime() {
		return pingAverage.currentValue();
	}

	public void reportThrottledPacketSendTime(long timeDiff) {
		node.throttledPacketSendAverage.report(timeDiff);
		Logger.minor(this, "Reporting throttled packet send time: "+timeDiff+" to "+getPeer());
	}
	
	public void setRemoteDetectedPeer(Peer p) {
		this.remoteDetectedPeer = p;
	}
	
	public Peer getRemoteDetectedPeer() {
		return remoteDetectedPeer;
	}

	public synchronized String getName() {
		return myName;
	}

	public int getRoutingBackoffLength() {
        synchronized(routingBackoffSync) {
			return routingBackoffLength;
		}
	}

	public long getRoutingBackedOffUntil() {
        synchronized(routingBackoffSync) {
			return routingBackedOffUntil;
		}
	}

	public synchronized String getLastBackoffReason() {
		return lastRoutingBackoffReason;
	}

	public synchronized String getPreviousBackoffReason() {
		return previousRoutingBackoffReason;
	}

	public synchronized void setLastBackoffReason(String s) {
		lastRoutingBackoffReason = s;
	}

	public synchronized boolean hasCompletedHandshake() {
		return completedHandshake;
	}
	
	public void addToLocalNodeSentMessagesToStatistic (Message m)
	{
	String messageSpecName;
	Long count;
	
		messageSpecName = m.getSpec().getName();
		//
		count = (Long)localNodeSentMessageTypes.get(messageSpecName);
		if (count == null)
		{
			count = new Long(1);
		}
		else
		{
			count = new Long(count.longValue() + 1);
		}
		//
		localNodeSentMessageTypes.put(messageSpecName,count);
	}
	
	public void addToLocalNodeReceivedMessagesFromStatistic (Message m)
	{
	String messageSpecName;
	Long count;
	
		messageSpecName = m.getSpec().getName();
		//
		count = (Long)localNodeReceivedMessageTypes.get(messageSpecName);
		if (count == null)
		{
			count = new Long(1);
		}
		else
		{
			count = new Long(count.longValue() + 1);
		}
		//
		localNodeReceivedMessageTypes.put(messageSpecName,count);
	}
	
	//FIXME: maybe return a copy insteed
	public Hashtable getLocalNodeSentMessagesToStatistic ()
	{
		return localNodeSentMessageTypes;
	}
	
	//FIXME: maybe return a copy insteed
	public Hashtable getLocalNodeReceivedMessagesFromStatistic ()
	{
		return localNodeReceivedMessageTypes;
	}

	synchronized USK getARK() {
		return myARK;
	}

	public synchronized void updateARK(FreenetURI newURI) {
		try {
			USK usk = USK.create(newURI);
			if(!myARK.equals(usk.copy(myARK.suggestedEdition), false)) {
				Logger.error(this, "Changing ARK not supported (and shouldn't be possible): from "+myARK+" to "+usk+" for "+this);
			} else if(myARK.suggestedEdition > usk.suggestedEdition) {
				Logger.minor(this, "Ignoring ARK edition decrease: "+myARK.suggestedEdition+" to "+usk.suggestedEdition+" for "+this);
			} else if(myARK.suggestedEdition < usk.suggestedEdition) {
				Logger.minor(this, "New ARK edition found");
				myARK = usk;
			} else if(myARK == null) {
				Logger.minor(this, "Setting ARK to "+usk+" was null on "+this);
				myARK = usk;
			}
		} catch (MalformedURLException e) {
			Logger.error(this, "ARK update failed: Could not parse permanent redirect (from USK): "+newURI+" : "+e, e);
		}
	}

	public void gotARK(SimpleFieldSet fs, long fetchedEdition) {
		try {
			synchronized(this) {
				handshakeCount = 0;
				// edition +1 because we store the ARK edition that we want to fetch.
				if(myARK.suggestedEdition < fetchedEdition+1)
					myARK = myARK.copy(fetchedEdition+1);
			}
			processNewNoderef(fs, true);
		} catch (FSParseException e) {
			Logger.error(this, "Invalid ARK update: "+e, e);
			// This is ok as ARKs are limited to 4K anyway.
			Logger.error(this, "Data was: \n"+fs.toString());
			synchronized(this) {
				handshakeCount = PeerNode.MAX_HANDSHAKE_COUNT;
			}
		}
	}

  public synchronized int getPeerNodeStatus() {
		return peerNodeStatus;
  }

  public String getPeerNodeStatusString() {
  	int status = getPeerNodeStatus();
  	if(status == Node.PEER_NODE_STATUS_CONNECTED)
  		return "CONNECTED";
  	if(status == Node.PEER_NODE_STATUS_ROUTING_BACKED_OFF)
  		return "BACKED OFF";
  	if(status == Node.PEER_NODE_STATUS_TOO_NEW)
  		return "TOO NEW";
  	if(status == Node.PEER_NODE_STATUS_TOO_OLD)
  		return "TOO OLD";
  	if(status == Node.PEER_NODE_STATUS_DISCONNECTED)
  		return "DISCONNECTED";
  	if(status == Node.PEER_NODE_STATUS_NEVER_CONNECTED)
  		return "NEVER CONNECTED";
  	if(status == Node.PEER_NODE_STATUS_DISABLED)
  		return "DISABLED";
  	if(status == Node.PEER_NODE_STATUS_LISTEN_ONLY)
  		return "LISTEN ONLY";
  	if(status == Node.PEER_NODE_STATUS_LISTENING)
  		return "LISTENING";
  	if(status == Node.PEER_NODE_STATUS_BURSTING)
  		return "BURSTING";
  	return "UNKNOWN STATUS";
  }

  public String getPeerNodeStatusCSSClassName() {
  	int status = getPeerNodeStatus();
  	if(status == Node.PEER_NODE_STATUS_CONNECTED)
  		return "peer_connected";
  	if(status == Node.PEER_NODE_STATUS_ROUTING_BACKED_OFF)
  		return "peer_backedoff";
  	if(status == Node.PEER_NODE_STATUS_TOO_NEW)
  		return "peer_too_new";
  	if(status == Node.PEER_NODE_STATUS_TOO_OLD)
  		return "peer_too_old";
  	if(status == Node.PEER_NODE_STATUS_DISCONNECTED)
  		return "peer_disconnected";
  	if(status == Node.PEER_NODE_STATUS_NEVER_CONNECTED)
  		return "peer_never_connected";
  	if(status == Node.PEER_NODE_STATUS_DISABLED)
  		return "peer_disconnected";  // **FIXME**
  	if(status == Node.PEER_NODE_STATUS_LISTEN_ONLY)
  		return "peer_disconnected";  // **FIXME**
  	if(status == Node.PEER_NODE_STATUS_LISTENING)
  		return "peer_disconnected";  // **FIXME**
  	if(status == Node.PEER_NODE_STATUS_BURSTING)
  		return "peer_disconnected";  // **FIXME**
  	return "peer_unknown_status";
  }

	public void setPeerNodeStatus(long now) {
		long localRoutingBackedOffUntil = getRoutingBackedOffUntil();
		synchronized(this) {
			int oldPeerNodeStatus = peerNodeStatus;
			if(isRoutable()) {  // Function use also updates timeLastConnected and timeLastRoutable
				peerNodeStatus = Node.PEER_NODE_STATUS_CONNECTED;
				if(now < localRoutingBackedOffUntil ) {
					peerNodeStatus = Node.PEER_NODE_STATUS_ROUTING_BACKED_OFF;
					if(!lastRoutingBackoffReason.equals(previousRoutingBackoffReason) || (previousRoutingBackoffReason == null)) {
						if(previousRoutingBackoffReason != null) {
							node.removePeerNodeRoutingBackoffReason(previousRoutingBackoffReason, this);
						}
						node.addPeerNodeRoutingBackoffReason(lastRoutingBackoffReason, this);
						previousRoutingBackoffReason = lastRoutingBackoffReason;
					}
				} else {
					if(previousRoutingBackoffReason != null) {
						node.removePeerNodeRoutingBackoffReason(previousRoutingBackoffReason, this);
						previousRoutingBackoffReason = null;
					}
				}
			} else if(isDisabled) {
				peerNodeStatus = Node.PEER_NODE_STATUS_DISABLED;
			} else if(isConnected() && verifiedIncompatibleNewerVersion) {
				peerNodeStatus = Node.PEER_NODE_STATUS_TOO_NEW;
			} else if(isConnected && verifiedIncompatibleOlderVersion) {
				peerNodeStatus = Node.PEER_NODE_STATUS_TOO_OLD;
			} else if(neverConnected) {
				peerNodeStatus = Node.PEER_NODE_STATUS_NEVER_CONNECTED;
			} else if(isListenOnly) {
				peerNodeStatus = Node.PEER_NODE_STATUS_LISTEN_ONLY;
			} else if(isBursting) {
				peerNodeStatus = Node.PEER_NODE_STATUS_BURSTING;
			} else if(isBurstOnly) {
				peerNodeStatus = Node.PEER_NODE_STATUS_LISTENING;
			} else {
				peerNodeStatus = Node.PEER_NODE_STATUS_DISCONNECTED;
			}
			if(!isConnected && (previousRoutingBackoffReason != null)) {
				node.removePeerNodeRoutingBackoffReason(previousRoutingBackoffReason, this);
				previousRoutingBackoffReason = null;
			}
			if(peerNodeStatus != oldPeerNodeStatus) {
			  node.removePeerNodeStatus( oldPeerNodeStatus, this );
			  node.addPeerNodeStatus( peerNodeStatus, this );
			}
		}
	}

	public String getIdentityString() {
    	return Base64.encode(identity);
    }

	public boolean isFetchingARK() {
		return arkFetcher.isFetching();
	}

	public synchronized int getHandshakeCount() {
		return handshakeCount;
	}
	
	public void enablePeer() {
		synchronized(this) {
			isDisabled = false;
		}
		setPeerNodeStatus(System.currentTimeMillis());
        node.peers.writePeers();
	}
	
	public void disablePeer() {
		synchronized(this) {
			isDisabled = true;
		}
		if(isConnected()) {
			forceDisconnect();
		}
    	arkFetcher.stop();
		setPeerNodeStatus(System.currentTimeMillis());
        node.peers.writePeers();
	}

	public synchronized boolean isDisabled() {
		return isDisabled;
	}
	
	public void setListenOnly(boolean setting) {
		synchronized(this) {
			isListenOnly = setting;
		}
		if(setting && isBurstOnly()) {
			setBurstOnly(false);
		}
		setPeerNodeStatus(System.currentTimeMillis());
        node.peers.writePeers();
	}

	public synchronized boolean isListenOnly() {
		return isListenOnly;
	}
	
	public void setBurstOnly(boolean setting) {
		synchronized(this) {
			isBurstOnly = setting;
		}
		if(setting && isListenOnly()) {
			setListenOnly(false);
		}
		long now = System.currentTimeMillis();
		if(!setting) {
			synchronized(this) {
				sendHandshakeTime = now;  // don't keep any long handshake delays we might have had under BurstOnly
			}
		}
		setPeerNodeStatus(now);
		node.peers.writePeers();
	}

	public synchronized boolean isBurstOnly() {
		return isBurstOnly;
	}

	/**
	 * Should the node be disconnected from immediately?
	 * This will return true if our lastGoodBuild has changed due to a timed mandatory.
	 */
	public synchronized boolean shouldDisconnectNow() {
		// TODO: We should disconnect here if "protocol version mismatch", maybe throwing an exception
		verifiedIncompatibleOlderVersion = invalidVersion();
		verifiedIncompatibleNewerVersion = reverseInvalidVersion();
		if(verifiedIncompatibleNewerVersion || verifiedIncompatibleOlderVersion) return true;
		return false;
	}

	protected synchronized void invalidate() {
		isRoutable = false;
		Logger.normal(this, "Invalidated "+this);
	}
	
	public synchronized boolean allowLocalAddresses() {
		return allowLocalAddresses;
	}
	
	private synchronized boolean neverConnected() {
		return neverConnected;
	}
}
