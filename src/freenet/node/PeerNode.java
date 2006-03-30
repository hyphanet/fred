package freenet.node;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.Vector;

import freenet.crypt.BlockCipher;
import freenet.crypt.DiffieHellmanContext;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.ciphers.Rijndael;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.Peer;
import freenet.io.comm.PeerContext;
import freenet.io.comm.PeerParseException;
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
    private boolean completedHandshake = false;
    
	private String lastGoodVersion; 
	
    /** For debugging/testing, set this to true to stop the
     * probabilistic decrement at the edges of the HTLs.
     */
    static boolean disableProbabilisticHTLs = false;

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
    
    /** Are we connected? If not, we need to start trying to
     * handshake.
     */
    private boolean isConnected;
    
    
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
    public PeerNode(SimpleFieldSet fs, Node node2) throws FSParseException, PeerParseException {
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
        String locationString = fs.get("location");
        if(locationString == null) throw new FSParseException("No location");
        currentLocation = new Location(locationString);

        // FIXME make mandatory once everyone has upgraded
        lastGoodVersion = fs.get("lastGoodVersion");
        
       	nominalPeer=new Vector();
        nominalPeer.removeAllElements();
        try{
        	String physical[]=fs.getAll("physical.udp");
        	if(physical==null){
        		Peer p = new Peer(fs.get("physical.udp"));
        		if(p != null)
        			nominalPeer.addElement(p);
        	}else{
	    		for(int i=0;i<physical.length;i++){		
					Peer p = new Peer(physical[i]);
				    if(!nominalPeer.contains(p)) 
				    	nominalPeer.addElement(p);
	    		}
        	}
        } catch (Exception e1) {
                throw new FSParseException(e1);
        }
        if(nominalPeer.isEmpty()) {
        	Logger.normal(this, "No IP addresses found");
        	detectedPeer = null;
        } else
        	detectedPeer=(Peer) nominalPeer.firstElement();
        
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
        
        randomizeMaxTimeBetweenPacketSends();
        swapRequestsInterval = new SimpleRunningAverage(50, Node.MIN_INTERVAL_BETWEEN_INCOMING_SWAP_REQUESTS);
        
        // Not connected yet; need to handshake
        isConnected = false;
               
        messagesToSendNow = new LinkedList();
        
        decrementHTLAtMaximum = node.random.nextFloat() < Node.DECREMENT_AT_MAX_PROB;
        decrementHTLAtMinimum = node.random.nextFloat() < Node.DECREMENT_AT_MIN_PROB;

        // FIXME maybe a simple binary RA would be better?
        pingNumber = node.random.nextLong();
        pingAverage = new SimpleRunningAverage(20, 1);
        throttledPacketSendAverage = new SimpleRunningAverage(20, 1);
    }

    private void randomizeMaxTimeBetweenPacketSends() {
        int x = Node.KEEPALIVE_INTERVAL;
        x += node.random.nextInt(x);
    }

    /**
     * Get my low-level address
     */
    public Peer getDetectedPeer() {
        return detectedPeer;
    }

    public Peer getPeer(){
    	return detectedPeer;
    }
    
    /**
     * Returns an array with the advertised addresses and the detected one
     */
    public Peer[] getHandshakeIPs(){
    	Peer[] p=null;
    	
    	if(detectedPeer == null && nominalPeer.size() == 0) return new Peer[0];
    	
    	if( ! nominalPeer.contains(detectedPeer)){
      		p= new Peer[1+nominalPeer.size()];
    		p[0]=detectedPeer;
    		for(int i=1;i<nominalPeer.size()+1;i++)
    			p[i]=(Peer) nominalPeer.get(i-1);
    	}else{
    		p = (Peer[]) nominalPeer.toArray(new Peer[nominalPeer.size()]);  		
    	}
    	// Hack for two nodes on the same IP that can't talk over inet for routing reasons
    	InetAddress localhost = node.localhostAddress;
    	InetAddress nodeIP = node.getPrimaryIPAddress();
    	if(nodeIP != null && nodeIP.equals(localhost)) return p;
    	InetAddress peerIP = detectedPeer.getAddress();
    	if(peerIP.equals(localhost)) return p;
	if(nodeIP != null && nodeIP.equals(peerIP)) {
    		Peer[] newPeers = new Peer[p.length+1];
    		System.arraycopy(p, 0, newPeers, 0, p.length);
    		newPeers[newPeers.length-1] = new Peer(node.localhostAddress, detectedPeer.getPort());
    		p = newPeers;
    	}
    	return p;
    }
    
    /**
     * What is my current keyspace location?
     */
    public Location getLocation() {
        return currentLocation;
    }
    
    /**
     * Is this node currently connected?
     * 
     * Note possible deadlocks! PeerManager calls this, we call
     * PeerManager in e.g. verified.
     */
    public boolean isConnected() {
        return isConnected;
    }

    /**
     * Send a message, off-thread, to this node.
     * @param msg The message to be sent.
     */
    public void sendAsync(Message msg, AsyncMessageCallback cb) throws NotConnectedException {
        Logger.minor(this, "Sending async: "+msg+" : "+cb+" on "+this);
        if(!isConnected) throw new NotConnectedException();
        MessageItem item = new MessageItem(msg, cb == null ? null : new AsyncMessageCallback[] {cb});
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

    /**
     * Disconnected e.g. due to not receiving a packet for ages.
     */
    public void disconnected() {
        Logger.normal(this, "Disconnected "+this);
        node.usm.onDisconnect(this);
        node.peers.disconnected(this);
        synchronized(this) {
            isConnected = false;
            if(currentTracker != null)
                currentTracker.disconnected();
            if(previousTracker != null)
                previousTracker.disconnected();
            if(unverifiedTracker != null)
                unverifiedTracker.disconnected();
            // Must null out to make other side prove it can
            // *receive* from us as well as send *to* us before
            // sending any more packets.
            currentTracker = previousTracker = unverifiedTracker = null;
        }
        node.lm.lostOrRestartedNode(this);
        sendHandshakeTime = System.currentTimeMillis();
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
        // Will usually indicate serious problems
        if(!dontLog)
            Logger.normal(this, "Requeueing "+messages.length+" messages on "+this);
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
        return (!isConnected) && 
                (now > sendHandshakeTime) &&
                !(hasLiveHandshake(now));
    }
    
    /**
     * Does the node have a live handshake in progress?
     * @param now The current time.
     */
    public boolean hasLiveHandshake(long now) {
        DiffieHellmanContext c = ctx;
        if(c != null)
            Logger.minor(this, "Last used: "+(now - c.lastUsedTime()));
        return !(c == null || now - c.lastUsedTime() > Node.HANDSHAKE_TIMEOUT);
    }

    boolean firstHandshake = true;
    
    /**
     * Call this method when a handshake request has been
     * sent.
     */
    public synchronized void sentHandshake() {
        long now = System.currentTimeMillis();
        if(invalidVersion() && !firstHandshake) {
            sendHandshakeTime = now + Node.MIN_TIME_BETWEEN_VERSION_PROBES
            	+ node.random.nextInt(Node.RANDOMIZED_TIME_BETWEEN_VERSION_PROBES);
        } else {
            sendHandshakeTime = now + Node.MIN_TIME_BETWEEN_HANDSHAKE_SENDS
        		+ node.random.nextInt(Node.RANDOMIZED_TIME_BETWEEN_HANDSHAKE_SENDS);
        }
        firstHandshake = false;
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
        node.usm.send(this, ping);
        Message msg;
        try {
            msg = node.usm.waitFor(MessageFilter.create().setTimeout(2000).setType(DMT.FNPPong).setField(DMT.PING_SEQNO, pingID));
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
    public void send(Message req) throws NotConnectedException {
        if(!isConnected) {
            Logger.error(this, "Tried to send "+req+" but not connected to "+this, new Exception("debug"));
            return;
        }
        node.usm.send(this, req);
    }

    /**
     * Update the Location to a new value.
     */
    public void updateLocation(double newLoc) {
        currentLocation.setValue(newLoc);
        node.peers.writePeers();
    }

    /**
     * Should we reject a swap request?
     */
    public synchronized boolean shouldRejectSwapRequest() {
        long now = System.currentTimeMillis();
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
        return false;
    }

    /**
     * IP on the other side appears to have changed...
     * @param newPeer The new address of the peer.
     */
    public void changedIP(Peer newPeer) {
        this.detectedPeer=newPeer;
    }

    /**
     * @return The current primary KeyTracker, or null if we
     * don't have one.
     */
    public KeyTracker getCurrentKeyTracker() {
        return currentTracker;
    }

    /**
     * @return The previous primary KeyTracker, or null if we
     * don't have one.
     */
    public KeyTracker getPreviousKeyTracker() {
        return previousTracker;
    }
    
    /**
     * @return The unverified KeyTracker, if any, or null if we
     * don't have one. The caller MUST call verified(KT) if a
     * decrypt succeeds with this KT.
     */
    public KeyTracker getUnverifiedKeyTracker() {
        return unverifiedTracker;
    }

    /**
     * @return short version of toString()
     */
    public String shortToString() {
        return super.toString()+"@"+detectedPeer+"@"+HexUtil.bytesToHex(identity);
    }

    public String toString() {
        // FIXME?
        return shortToString();
    }
    
    /**
     * Update timeLastReceivedPacket
     * @throws NotConnectedException 
     */
    synchronized void receivedPacket() throws NotConnectedException {
        if(isConnected == false && unverifiedTracker == null) {
            Logger.error(this, "Received packet while disconnected!: "+this, new Exception("error"));
            throw new NotConnectedException();
        }
        timeLastReceivedPacket = System.currentTimeMillis();
    }

    /**
     * Update timeLastSentPacket
     */
    public void sentPacket() {
        timeLastSentPacket = System.currentTimeMillis();
        randomizeMaxTimeBetweenPacketSends();
    }

    public DiffieHellmanContext getDHContext() {
        return ctx;
    }

    public void setDHContext(DiffieHellmanContext ctx2) {
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
    public synchronized boolean completedHandshake(long thisBootID, byte[] data, int offset, int length, BlockCipher encCipher, byte[] encKey, Peer replyTo, boolean unverified) {
    	completedHandshake = true;
        bogusNoderef = false;
        try {
            // First, the new noderef
            processNewNoderef(data, offset, length);
        } catch (FSParseException e1) {
            bogusNoderef = true;
            Logger.error(this, "Failed to parse new noderef for "+this+": "+e1, e1);
            // Treat as invalid version
        }
        if(invalidVersion()) {
            Logger.normal(this, "Not connecting to "+this+" - invalid version "+version);
            // Update the next time to check
            sentHandshake();
            isConnected = false;
            node.peers.disconnected(this);
            return false;
        }
        KeyTracker newTracker = new KeyTracker(this, encCipher, encKey);
        changedIP(replyTo);
        if(thisBootID != this.bootID) {
            connectedTime = System.currentTimeMillis();
            Logger.minor(this, "Changed boot ID from "+bootID+" to "+thisBootID);
            isConnected = false; // Will be reset below
            if(previousTracker != null) {
                KeyTracker old = previousTracker;
                previousTracker = null;
                old.completelyDeprecated(newTracker);
            }
            previousTracker = null;
            if(currentTracker != null) {
                KeyTracker old = currentTracker;
                currentTracker = null;
                old.completelyDeprecated(newTracker);
            }
            this.bootID = thisBootID;
            node.lm.lostOrRestartedNode(this);
        } // else it's a rekey
        
        if(unverified) {
            unverifiedTracker = newTracker;
            ctx = null;
            sentHandshake();
        } else {
            previousTracker = currentTracker;
            currentTracker = newTracker;
            unverifiedTracker = null;
            if(previousTracker != null)
                previousTracker.deprecated();
            isConnected = true;
            ctx = null;
        }
        if(!isConnected)
        	node.peers.disconnected(this);
        Logger.normal(this, "Completed handshake with "+this+" on "+replyTo+" - current: "+currentTracker+" old: "+previousTracker+" unverified: "+unverifiedTracker+" bootID: "+thisBootID);
        try {
			receivedPacket();
		} catch (NotConnectedException e) {
			Logger.error(this, "Disconnected in completedHandshake with "+this);
			return true; // i suppose
		}
		if(isConnected)
			node.peers.addConnectedPeer(this);
        sentInitialMessages = false;
        return true;
    }

    boolean sentInitialMessages = false;
    
    void maybeSendInitialMessages() {
        synchronized(this) {
            if(sentInitialMessages) return;
            if(currentTracker != null)
                sentInitialMessages = true;
            else return;
        }
        sendInitialMessages();
    }
    
    /**
     * Send any high level messages that need to be sent on connect.
     */
    private void sendInitialMessages() {
        Message locMsg = DMT.createFNPLocChangeNotification(node.lm.loc.getValue());
        Message ipMsg = DMT.createFNPDetectedIPAddress(detectedPeer);
        
        try {
            sendAsync(locMsg, null);
            sendAsync(ipMsg, null);
        } catch (NotConnectedException e) {
            Logger.error(this, "Completed handshake but disconnected!!!", new Exception("error"));
        }
    }

    /**
     * Called when a packet is successfully decrypted on a given
     * KeyTracker for this node. Will promote the unverifiedTracker
     * if necessary.
     */
    public void verified(KeyTracker tracker) {
    	synchronized(this) {
        if(tracker == unverifiedTracker) {
            Logger.minor(this, "Promoting unverified tracker "+tracker);
            if(previousTracker != null) {
                previousTracker.completelyDeprecated(tracker);
            }
            previousTracker = currentTracker;
            if(previousTracker != null)
                previousTracker.deprecated();
            currentTracker = unverifiedTracker;
            unverifiedTracker = null;
            isConnected = true;
            ctx = null;
            maybeSendInitialMessages();
        } else return;
    	}
        node.peers.addConnectedPeer(this);
    }
    
    private synchronized boolean invalidVersion() {
        return bogusNoderef || (!Version.checkGoodVersion(version));
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
        processNewNoderef(fs);
    }

    /**
     * Process a new nodereference, as a SimpleFieldSet.
     */
    private void processNewNoderef(SimpleFieldSet fs) throws FSParseException {
        Logger.minor(this, "Parsing: "+fs);
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
        if(newVersion == null) throw new FSParseException("No version");
        if(!newVersion.equals(version))
            changedAnything = true;
        version = newVersion;
        String locationString = fs.get("location");
        if(locationString == null) throw new FSParseException("No location");
        Location loc = new Location(locationString);
        if(!loc.equals(currentLocation)) changedAnything = true;
        currentLocation = loc;

        if(nominalPeer==null)
        	nominalPeer=new Vector();
        nominalPeer.removeAllElements();
        
        lastGoodVersion = fs.get("lastGoodVersion");
        
        Peer[] oldPeers = (Peer[]) nominalPeer.toArray(new Peer[nominalPeer.size()]);
        
        try{
        	String physical[]=fs.getAll("physical.udp");
        	if(physical==null){
        		Peer p = new Peer(fs.get("physical.udp"));
        		nominalPeer.addElement(p);
        	}else{
	    		for(int i=0;i<physical.length;i++){		
					Peer p = new Peer(physical[i]);
				    if(!nominalPeer.contains(p)) 
				    	nominalPeer.addElement(p);
	    		}
        	}
        } catch (Exception e1) {
                throw new FSParseException(e1);
        }
        
        if(!Arrays.equals(oldPeers, nominalPeer.toArray(new Peer[nominalPeer.size()])))
        	changedAnything = true;
        
        if(nominalPeer.isEmpty()) {
        	Logger.normal(this, "No physical.udp");
        	// detectedPeer stays as it is
        } else {
            /* yes, we pick up a random one : it will be updated on handshake */
            detectedPeer=(Peer) nominalPeer.firstElement();
        }
        String name = fs.get("myName");
        if(name == null) throw new FSParseException("No name");
        if(!name.equals(myName)) changedAnything = true;
        myName = name;
        if(changedAnything) node.peers.writePeers();
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
                    node.packetMangler.processOutgoing(null, 0, 0, tracker);
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
            long t = tracker.getNextUrgentTime();
            if(t < now) {
                try {
                    node.packetMangler.processOutgoing(null, 0, 0, tracker);
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
        return 
        	(isConnected ? "CONNECTED   " : "DISCONNECTED") + " " + getPeer()+" "+myName+" "+currentLocation.getValue()+" "+getVersion()+" backoff: "+backoffLength+" ("+(Math.max(backedOffUntil - System.currentTimeMillis(),0))+")";
    }
    
    public String getFreevizOutput() {
    	return
    	
       		getStatus()+"|"+ Base64.encode(identity);
    }
    public String getVersion(){
	    return version;
    }

    /**
     * Write our noderef to disk
     */
    public void write(Writer w) throws IOException {
        SimpleFieldSet fs = exportFieldSet();
        fs.writeTo(w);
    }

    /**
     * Export our noderef as a SimpleFieldSet
     */
    private SimpleFieldSet exportFieldSet() {
        SimpleFieldSet fs = new SimpleFieldSet(false);
        if(lastGoodVersion != null)
        	fs.put("lastGoodVersion", lastGoodVersion);
        for(int i=0;i<nominalPeer.size();i++)
        	fs.put("physical.udp", nominalPeer.get(i).toString());
        fs.put("base64", "true");
        fs.put("identity", Base64.encode(identity));
        fs.put("location", Double.toString(currentLocation.getValue()));
        fs.put("testnet", Boolean.toString(testnetEnabled));
        fs.put("version", version);
        fs.put("myName", myName);
        return fs;
    }

    /**
     * @return The time at which we last connected (or reconnected).
     */
    public long timeLastConnected() {
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
            if(kt != null && item.kt == kt) {
                kt.resendPacket(item.packetNumber);
                continue;
            }
            kt = prev;
            if(kt != null && item.kt == kt) {
                kt.resendPacket(item.packetNumber);
                continue;
            }
            kt = unv;
            if(kt != null && item.kt == kt) {
                kt.resendPacket(item.packetNumber);
                continue;
            }
            // Doesn't match any of these, need to resend the data
            kt = cur == null ? unv : cur;
            if(kt == null) {
                Logger.error(this, "No tracker to resend packet "+item.packetNumber+" on");
                continue;
            }
            MessageItem mi = new MessageItem(item.buf, item.callbacks, true);
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

	private final Object backoffSync = new Object();
	
	public boolean isBackedOff() {
		synchronized(backoffSync) {
			if(System.currentTimeMillis() < backedOffUntil) {
				Logger.minor(this, "Is backed off");
				return true;
			} else return false;
		}
	}
	
	long backedOffUntil = -1;
	/** Initial nominal backoff length */
	final int INITIAL_BACKOFF_LENGTH = 5000;
	/** Double every time */
	final int BACKOFF_MULTIPLIER = 2;
	/** Maximum: 24 hours */
	final int MAX_BACKOFF_LENGTH = 24*60*60*1000;
	/** Current nominal backoff length */
	int backoffLength = INITIAL_BACKOFF_LENGTH;
	
	/**
	 * Got a local RejectedOverload.
	 * Back off this node for a while.
	 */
	public void localRejectedOverload() {
		Logger.minor(this, "Local rejected overload on "+this);
		synchronized(backoffSync) {
			long now = System.currentTimeMillis();
			// Don't back off any further if we are already backed off
			if(now > backedOffUntil) {
				backoffLength = backoffLength * BACKOFF_MULTIPLIER;
				if(backoffLength > MAX_BACKOFF_LENGTH)
					backoffLength = MAX_BACKOFF_LENGTH;
				int x = node.random.nextInt(backoffLength);
				backedOffUntil = now + x;
				Logger.minor(this, "Backing off: backoffLength="+backoffLength+", until "+x+"ms on "+getPeer());
			} else {
				Logger.minor(this, "Ignoring localRejectedOverload: "+(backedOffUntil-now)+"ms remaining on backoff on "+getPeer());
			}
		}
	}
	
	/**
	 * Didn't get RejectedOverload.
	 * Reset backoff.
	 */
	public void successNotOverload() {
		Logger.minor(this, "Success not overload on "+this);
		synchronized(backoffSync) {
			long now = System.currentTimeMillis();
			// Don't un-backoff if still backed off
			if(now > backedOffUntil) {
				backoffLength = INITIAL_BACKOFF_LENGTH;
				Logger.minor(this, "Resetting backoff on "+getPeer());
			} else {
				Logger.minor(this, "Ignoring successNotOverload: "+(backedOffUntil-now)+"ms remaining on backoff on "+getPeer());
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
	final RunningAverage throttledPacketSendAverage;
	
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
			sendAsync(msg, null);
		} catch (NotConnectedException e) {
			synchronized(pingSync) {
				pingsSentTimes.removeKey(lPingNo);
			}
		}
	}

	public void receivedLinkPong(long id) {
		Long lid = new Long(id);
		long startTime;
		synchronized(pingSync) {
			Long s = (Long) pingsSentTimes.get(lid);
			if(s == null) {
				Logger.normal(this, "Dropping ping "+id+" on "+this);
				return;
			}
			startTime = s.longValue();
			pingsSentTimes.removeKey(lid);
			long now = System.currentTimeMillis();
			pingAverage.report(now - startTime);
			Logger.minor(this, "Reporting ping time to "+this+" : "+(now - startTime));
		}
	}

	public double averagePingTime() {
		return pingAverage.currentValue();
	}

	public void reportThrottledPacketSendTime(long timeDiff) {
		throttledPacketSendAverage.report(timeDiff);
		Logger.minor(this, "Reporting throttled packet send time: "+timeDiff+" to "+getPeer());
	}

	public void setRemoteDetectedPeer(Peer p) {
		this.remoteDetectedPeer = p;
	}
	
	public Peer getRemoteDetectedPeer() {
		return remoteDetectedPeer;
	}

	public String getName() {
		return myName;
	}

	public int getBackoffLength() {
		return this.backoffLength;
	}

	public long getBackedOffUntil() {
		return backedOffUntil;
	}

	public boolean hasCompletedHandshake() {
		return completedHandshake;
	}
}

