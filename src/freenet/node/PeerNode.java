package freenet.node;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import freenet.crypt.BlockCipher;
import freenet.crypt.DiffieHellmanContext;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.ciphers.Rijndael;
import freenet.io.comm.DMT;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.Peer;
import freenet.io.comm.PeerContext;
import freenet.io.comm.PeerParseException;
import freenet.io.xfer.PacketThrottle;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
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

    /** For debugging/testing, set this to true to stop the
     * probabilistic decrement at the edges of the HTLs.
     */
    public static boolean disableProbabilisticHTLs = false;

    /** My low-level address for SocketManager purposes */
    private Peer peer;
    
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
    
    /** What is the current maximum inter-packet time (this is
     * randomized to make it hard to profile; it is essentially
     * a keepalive though).
     */
    private long maxTimeBetweenPacketSends;
    
    /** When did we last receive a packet? */
    private long timeLastReceivedPacket;
    
    /** Maximum time between packet receives. In other words,
     * after this amount of time, we assume the node is dead.
     */
    private int maxTimeBetweenPacketReceives;

    /** Are we connected? If not, we need to start trying to
     * handshake.
     */
    private boolean isConnected;

    /** Throttle, used by data transfers */
    private PacketThrottle throttle;
    
    /** Current location in the keyspace */
    private Location currentLocation;
    
    /** Node identity; for now a block of data, in future a
     * public key (FIXME). Cannot be changed.
     */
    final byte[] identity;
    
    /** Hash of node identity. Used as setup key. */
    final byte[] identityHash;
    
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
        String identityString = fs.get("identity");
        try {
            identity = HexUtil.hexToBytes(identityString);
        } catch (NumberFormatException e) {
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
        version = fs.get("version");
        String locationString = fs.get("location");
        if(locationString == null) throw new FSParseException("No location");
        currentLocation = new Location(locationString);
        String physical = fs.get("physical.udp");
        if(physical == null) throw new FSParseException("No physical.udp");
        peer = new Peer(physical);
        String name = fs.get("myName");
        if(name == null) throw new FSParseException("No name");
        myName = name;
        
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
                                "\nThis hash: "+HexUtil.bytesToHex(setupKeyHash));
        
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
        
        randomizeMaxTimeBetweenPacketReceives();
        randomizeMaxTimeBetweenPacketSends();
        swapRequestsInterval = new SimpleRunningAverage(50, Node.MIN_INTERVAL_BETWEEN_INCOMING_SWAP_REQUESTS);
        
        // Not connected yet; need to handshake
        isConnected = false;
        
        throttle = PacketThrottle.getThrottle(peer, Node.PACKET_SIZE);
        
        messagesToSendNow = new LinkedList();
        
        decrementHTLAtMaximum = node.random.nextFloat() < Node.DECREMENT_AT_MAX_PROB;
        decrementHTLAtMinimum = node.random.nextFloat() < Node.DECREMENT_AT_MIN_PROB;
    }

    private void randomizeMaxTimeBetweenPacketSends() {
        int x = Node.KEEPALIVE_INTERVAL;
        x += node.random.nextInt(x);
        maxTimeBetweenPacketSends = x;
    }

    private void randomizeMaxTimeBetweenPacketReceives() {
        int x = Node.MAX_PEER_INACTIVITY;
        x += node.random.nextInt(x);
        maxTimeBetweenPacketReceives = x;
    }

    /**
     * Get my low-level address
     */
    public Peer getPeer() {
        return peer;
    }
    
    /**
     * What is my current keyspace location?
     */
    public Location getLocation() {
        return currentLocation;
    }
    
    /**
     * Is this node currently connected?
     */
    public boolean isConnected() {
        return isConnected;
    }

    /**
     * Send a message, off-thread, to this node.
     * @param msg The message to be sent.
     */
    public void sendAsync(Message msg, AsyncMessageCallback cb) throws NotConnectedException {
        Logger.minor(this, "Sending async: "+msg+" : "+cb);
        if(!isConnected) throw new NotConnectedException();
        MessageItem item = new MessageItem(msg, cb);
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
        Logger.minor(this, "Disconnected "+this);
        synchronized(this) {
            isConnected = false;
            if(currentTracker != null)
                currentTracker.disconnected();
            if(previousTracker != null)
                previousTracker.disconnected();
            if(unverifiedTracker != null)
                unverifiedTracker.disconnected();
        }
        node.lm.lostOrRestartedNode(this);
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
    
    public void requeueMessageItems(MessageItem[] messages) {
        // Will usually indicate serious problems
        Logger.error(this, "Requeueing "+messages.length+" messages on "+this);
        synchronized(messagesToSendNow) {
            for(int i=0;i<messages.length;i++)
                messagesToSendNow.add(messages[i]);
        }
    }

    /**
     * @return The time at which we must send a packet, even if
     * it means it will only contains ack requests etc., or
     * Long.MAX_VALUE if we have no pending ack request/acks/etc.
     */
    public long getNextUrgentTime() {
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
    public void sentHandshake() {
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
        return this.maxTimeBetweenPacketReceives;
    }
    
    /**
     * Low-level ping this node.
     * @return True if we received a reply inside 2000ms.
     * (If we have heavy packet loss, it can take that long to resend).
     */
    public boolean ping(int pingID) throws NotConnectedException {
        Message ping = DMT.createFNPPing(pingID);
        node.usm.send(this, ping);
        Message msg = 
            node.usm.waitFor(MessageFilter.create().setTimeout(2000).setType(DMT.FNPPong).setField(DMT.PING_SEQNO, pingID));
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
     * REDFLAG: If we ever implement queueing, do it here.
     */
    public void send(Message req) throws NotConnectedException {
        if(!isConnected) {
            Logger.error(this, "Tried to send "+req+" but not connected", new Exception("debug"));
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
     * @param peer The new address of the peer.
     */
    public void changedIP(Peer peer) {
        this.peer = peer;
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
        return super.toString()+"@"+peer.toString()+"@"+HexUtil.bytesToHex(identity);
    }

    public String toString() {
        // FIXME?
        return shortToString();
    }
    
    /**
     * Update timeLastReceivedPacket
     */
    synchronized void receivedPacket() {
        timeLastReceivedPacket = System.currentTimeMillis();
        randomizeMaxTimeBetweenPacketReceives();
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
     */
    public synchronized void completedHandshake(long thisBootID, byte[] data, int offset, int length, BlockCipher encCipher, byte[] encKey, Peer replyTo, boolean unverified) {
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
            return;
        }
        KeyTracker newTracker = new KeyTracker(this, encCipher, encKey);
        changedIP(replyTo);
        if(thisBootID != this.bootID) {
            connectedTime = System.currentTimeMillis();
            Logger.minor(this, "Changed boot ID from "+bootID+" to "+thisBootID);
            if(previousTracker != null)
                previousTracker.completelyDeprecated(newTracker);
            previousTracker = null;
            if(currentTracker != null)
                currentTracker.completelyDeprecated(newTracker);
            currentTracker = null;
            this.bootID = thisBootID;
            isConnected = false;
            node.lm.lostOrRestartedNode(this);
        } // else it's a rekey
        
        if(unverified) {
            unverifiedTracker = newTracker;
            ctx.getCipher(); // update timestamp
        } else {
            previousTracker = currentTracker;
            currentTracker = newTracker;
            unverifiedTracker = null;
            if(previousTracker != null)
                previousTracker.deprecated();
            isConnected = true;
            ctx = null;
        }
        Logger.normal(this, "Completed handshake with "+this+" on "+replyTo+" - current: "+currentTracker+" old: "+previousTracker+" unverified: "+unverifiedTracker+" bootID: "+thisBootID);
        receivedPacket();
        node.peers.addConnectedPeer(this);
        if(!unverified)
            sendInitialMessages();
    }

    /**
     * Send any high level messages that need to be sent on connect.
     */
    private void sendInitialMessages() {
        Message msg = DMT.createFNPLocChangeNotification(node.lm.loc.getValue());
        
        try {
            sendAsync(msg, null);
        } catch (NotConnectedException e) {
            Logger.error(this, "Completed handshake but disconnected!!!", new Exception("error"));
        }
    }

    /**
     * Called when a packet is successfully decrypted on a given
     * KeyTracker for this node. Will promote the unverifiedTracker
     * if necessary.
     */
    public synchronized void verified(KeyTracker tracker) {
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
            sendInitialMessages();
        }
    }
    
    private boolean invalidVersion() {
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
            int outputPointer = 0;
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
            fs = new SimpleFieldSet(br);
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
            byte[] newIdentity = HexUtil.hexToBytes(identityString);
            if(!Arrays.equals(newIdentity, identity))
                throw new FSParseException("Identity changed!!");
        } catch (NumberFormatException e) {
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
        String physical = fs.get("physical.udp");
        if(physical == null) throw new FSParseException("No physical.udp");
        try {
            Peer p = new Peer(physical);
            if(!p.equals(peer)) changedAnything = true;
            peer = p;
        } catch (PeerParseException e1) {
            throw new FSParseException(e1);
        }
        String name = fs.get("myName");
        if(name == null) throw new FSParseException("No name");
        if(!name.equals(myName)) changedAnything = true;
        myName = name;
        if(changedAnything) node.peers.writePeers();
    }

    /**
     * Send a payload-less packet on either key if necessary.
     */
    public void sendAnyUrgentNotifications() {
        Logger.minor(this, "sendAnyUrgentNotifications");
        long now = System.currentTimeMillis();
        KeyTracker tracker = currentTracker;
        if(tracker != null) {
            long t = tracker.getNextUrgentTime();
            if(t < now) {
                try {
                    node.packetMangler.processOutgoing(null, 0, 0, tracker);
                } catch (NotConnectedException e) {
                    // Ignore
                } catch (KeyChangedException e) {
                    // Ignore
                }
            }
        }
        tracker = previousTracker;
        if(tracker != null) {
            long t = tracker.getNextUrgentTime();
            if(t < now) {
                try {
                    node.packetMangler.processOutgoing(null, 0, 0, tracker);
                } catch (NotConnectedException e) {
                    // Ignore
                } catch (KeyChangedException e) {
                    // Ignore
                }
            }
        }
    }

    public String getStatus() {
        return getPeer().toString()+" "+
        	(isConnected ? "CONNECTED" : "DISCONNECTED") + " "+myName+" "+currentLocation.getValue();
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
        SimpleFieldSet fs = new SimpleFieldSet();
        fs.put("physical.udp", peer.toString());
        fs.put("identity", HexUtil.bytesToHex(identity));
        fs.put("location", Double.toString(currentLocation.getValue()));
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
}
