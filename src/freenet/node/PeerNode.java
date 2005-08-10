package freenet.node;

import java.util.LinkedList;
import java.util.ListIterator;

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
    
    /** Packets sent/received on the current preferred key */
    private KeyTracker currentTracker;
    
    /** Previous key - has a separate packet number space */
    private KeyTracker previousTracker;
    
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
    
    /** The Node we serve */
    final Node node;
    
    /** Messages to send ASAP */
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
    
    /** Setup key - this is a shared secret used to ensure privacy in
     * connection negotiations. It is known only by nodes which have the
     * node reference, so a passive eavesdropper who doesn't know us will
     * see random data. */
    private final byte[] setupKey;

    /** Setup cipher - see setupKey for description. */
    private final BlockCipher setupCipher;
    
    /** The context object for the currently running negotiation. */
    private DiffieHellmanContext ctx;
    
    /** The other side's boot ID. This is a random number generated
     * at startup.
     */
    private long bootID;
    
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
        version = fs.get("version");
        String locationString = fs.get("location");
        if(locationString == null) throw new FSParseException("No location");
        currentLocation = new Location(locationString);
        String physical = fs.get("physical.udp");
        if(physical == null) throw new FSParseException("No physical.udp");
        peer = new Peer(physical);
        String setupKeyString = fs.get("setupKey");
        if(setupKeyString == null) throw new FSParseException("No setup key");
        try {
            setupKey = HexUtil.hexToBytes(setupKeyString);
        } catch (NumberFormatException e) {
            throw new FSParseException(e);
        }
        if(setupKey.length != Node.SYMMETRIC_KEY_LENGTH) {
            throw new FSParseException("Setup key too short");
        }
        try {
            setupCipher = new Rijndael(256,256);
            setupCipher.initialize(setupKey);
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
        sendHandshakeTime = -1;
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
    public void sendAsync(Message msg) {
        messagesToSendNow.addLast(msg);
        synchronized(node.ps) {
            node.ps.notifyAll();
        }
    }

    /**
     * @return The last time we received a packet.
     */
    public long lastReceivedPacketTime() {
        return timeLastReceivedPacket;
    }

    /**
     * Disconnected e.g. due to not receiving a packet for ages.
     */
    public void disconnected() {
        Logger.minor(this, "Disconnected "+this);
        isConnected = false;
    }

    /**
     * Grab all queued Message's.
     * @return Null if no messages are queued, or an array of
     * Message's.
     */
    public Message[] grabQueuedMessages() {
        synchronized(messagesToSendNow) {
            if(messagesToSendNow.size() == 0) return null;
            Message[] messages = new Message[messagesToSendNow.size()];
            messages = (Message[])messagesToSendNow.toArray(messages);
            messagesToSendNow.clear();
            return messages;
        }
    }

    /**
     * @return The time at which we must send a packet, even if
     * it means it will only contains ack requests etc., or
     * Long.MAX_VALUE if we have no pending ack request/acks/etc.
     */
    public long getNextUrgentTime() {
        return Math.min(currentTracker.getNextUrgentTime(),
                previousTracker.getNextUrgentTime());
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
    private boolean hasLiveHandshake(long now) {
        return !(ctx == null || now - ctx.lastUsedTime() > 2*Node.HANDSHAKE_TIMEOUT);
    }

    /**
     * Call this method when a handshake request has been
     * sent.
     */
    public void sentHandshake() {
        sendHandshakeTime = System.currentTimeMillis() + Node.MIN_TIME_BETWEEN_HANDSHAKE_SENDS
        	+ node.random.nextInt(Node.RANDOMIZED_TIME_BETWEEN_HANDSHAKE_SENDS);
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
    void receivedPacket() {
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

    /**
     * @return
     */
    public BlockCipher getAuthKey() {
        return setupCipher;
    }

    public DiffieHellmanContext getDHContext() {
        return ctx;
    }

    public void setDHContext(DiffieHellmanContext ctx2) {
        this.ctx = ctx2;
    }

    /**
     * Called when we have completed a handshake, and have a new session key.
     * Creates a new tracker and demotes the old one. Deletes the old one if
     * the bootID isn't recognized.
     * @param bootID
     * @param encKey
     * @param replyTo
     */
    public synchronized void completedHandshake(long thisBootID, BlockCipher encKey, Peer replyTo) {
        KeyTracker newTracker = new KeyTracker(this, encKey);
        changedIP(replyTo);
        previousTracker = currentTracker;
        currentTracker = newTracker;
        previousTracker.deprecated();
        
        if(thisBootID != this.bootID) {
            // Wipe old KeyTracker
            previousTracker = null;
        } // else it's a rekey
        Logger.normal(this, "Completed handshake with "+this+" on "+replyTo);
        ctx = null;
        Message msg = DMT.createFNPLocChangeNotification(node.lm.loc.getValue());
        sendAsync(msg);
    }

    /**
     * Send a payload-less packet on either key if necessary.
     */
    public void sendAnyUrgentNotifications() {
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
}
