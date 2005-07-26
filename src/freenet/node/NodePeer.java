package freenet.node;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.ListIterator;

import freenet.crypt.BlockCipher;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.ciphers.Rijndael;
import freenet.io.comm.DMT;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.Peer;
import freenet.io.comm.PeerContext;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.UdpSocketManager;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.math.RunningAverage;
import freenet.support.math.TimeDecayingRunningAverage;

/**
 * @author amphibian
 * 
 * Another node.
 */
public class NodePeer implements PeerContext {
    
    /**
     * Tracks which packet numbers we have received.
     * Implemented as a sorted list since this is simplest and
     * it's unlikely it will be very long in practice. The 512-
     * packet window provides a practical limit.
     */
    public class ReceivedPackets {
        
        LinkedList ranges;
        int lowestSeqNumber;
        int highestSeqNumber;
        
        ReceivedPackets() {
            ranges = new LinkedList();
            lowestSeqNumber = -1;
            highestSeqNumber = -1;
        }
        
        class Range {
            int start; // inclusive
            int end;   // inclusive
            
            public String toString() {
                return "Range:"+start+"->"+end;
            }
        }
        
        /**
         * We received a packet!
         * @param seqNumber The number of the packet.
         * @return True if we stored the packet. False if it is out
         * of range of the current window.
         */
        public synchronized boolean got(int seqNumber) {
            if(seqNumber < 0) throw new IllegalArgumentException();
            if(ranges.isEmpty()) {
                Range r = new Range();
                r.start = r.end = lowestSeqNumber = highestSeqNumber = seqNumber;
                ranges.addFirst(r);
                return true;
            } else {
                ListIterator li = ranges.listIterator();
                Range r = (Range)li.next();
                int firstSeq = r.end;
                if(seqNumber - firstSeq > 512) {
                    // Delete first item
                    li.remove();
                    r = (Range)li.next();
                    lowestSeqNumber = r.start;
                }
                while(true) {
                    if(seqNumber == r.start-1) {
                        r.start--;
                        if(li.hasPrevious()) {
                            Range r1 = (Range) li.previous();
                            if(r1.end == seqNumber-1) {
                                r.start = r1.start;
                                li.remove();
                            }
                        } else {
                            lowestSeqNumber = seqNumber;
                        }
                        return true;
                    }
                    if(seqNumber < r.start-1) {
                        if(highestSeqNumber - seqNumber > 512) {
                            // Out of window, don't store
                            return false;
                        }
                        Range r1 = new Range();
                        r1.start = r1.end = seqNumber;
                        li.previous(); // move cursor back
                        if(!li.hasPrevious()) // inserting at start??
                            lowestSeqNumber = seqNumber;
                        li.add(r1);
                        return true;
                    }
                    if(seqNumber >= r.start && seqNumber <= r.end) {
                        // Duh
                        return true;
                    }
                    if(seqNumber == r.end+1) {
                        r.end++;
                        if(li.hasNext()) {
                            Range r1 = (Range) li.next();
                            if(r1.start == seqNumber+1) {
                                r.end = r1.end;
                                li.remove();
                            }
                        } else {
                            highestSeqNumber = seqNumber;
                        }
                        return true;
                    }
                    if(seqNumber > r.end+1) {
                        if(!li.hasNext()) {
                            // This is the end of the list
                            Range r1 = new Range();
                            r1.start = r1.end = highestSeqNumber = seqNumber;
                            li.add(r1);
                            return true;
                        }
                    }
                    r = (Range) li.next();
                }
            }
        }

        /**
         * Have we received packet #seqNumber??
         * @param seqNumber
         * @return
         */
        public synchronized boolean contains(int seqNumber) {
            if(seqNumber > highestSeqNumber)
                return false;
            if(seqNumber == highestSeqNumber)
                return true;
            if(seqNumber <= lowestSeqNumber)
                return true;
            if(highestSeqNumber - seqNumber > 512)
                return true; // Assume we have since out of window
            Iterator i = ranges.iterator();
            Range last = null;
            for(;i.hasNext();) {
                Range r = (Range)i.next();
                if(r.start > r.end) {
                    Logger.error(this, "Bad Range: "+r);
                }
                if(last != null && r.start < last.end) {
                    Logger.error(this, "This range: "+r+" but last was: "+last);
                }
                if(r.start <= seqNumber && r.end >= seqNumber)
                    return true;
            }
            return false;
        }

        /**
         * @return The highest packet number seen so far.
         */
        public int highest() {
            return highestSeqNumber;
        }
        
        public String toString() {
            StringBuffer sb = new StringBuffer();
            sb.append(super.toString());
            sb.append(": max=");
            sb.append(highestSeqNumber);
            sb.append(", min=");
            sb.append(lowestSeqNumber);
            sb.append(", ranges=");
            synchronized(this) {
                Iterator i = ranges.iterator();
                while(i.hasNext()) {
                    Range r = (Range) i.next();
                    sb.append(r.start);
                    sb.append('-');
                    sb.append(r.end);
                    if(i.hasNext()) sb.append(',');
                }
            }
            return sb.toString();
        }
    }
    
    NodePeer(Location loc, Peer contact, byte[] nodeIdentity, Node n, PacketSender ps) {
        currentLocation = loc;
        peer = contact;
        sentPacketsBySequenceNumber = new HashMap();
        ackQueue = new LinkedList();
        resendRequestQueue = new LinkedList();
        ackRequestQueue = new LinkedList();
        node = n;
        usm = node.usm;
        // FIXME!! Session key should be set up via PK negotiation
        // Hack here: sesskey = H(identity1) XOR H(identity2)
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new Error(e);
        }
        this.nodeIdentity = nodeIdentity;
        byte[] key = md.digest(nodeIdentity);
        byte[] okey = node.identityHash;
        for(int i=0;i<key.length;i++)
            key[i] ^= okey[i];
        try {
            sessionCipher = new Rijndael(256,128);
        } catch (UnsupportedCipherException e1) {
            throw new Error(e1);
        }
        sessionCipher.initialize(key);
        this.packetSender = ps;
        // FIXME: this is a debugging aid, maybe we should keep it?
        outgoingPacketNumber = node.random.nextInt(100000);
        //Logger.minor(this, "outgoingPacketNumber initialized to "+outgoingPacketNumber);
        // 10 extra hops on average at the start (for cover)
        decrementAtMax = node.random.nextDouble() <= 0.1;
        // 5 extra hops on average at the end (to prevent probing and similar attacks)
        decrementAtMin = node.random.nextDouble() <= 0.25;
    }
    
    /**
     * @param fs
     */
    public NodePeer(SimpleFieldSet fs, Node node) throws FSParseException, PeerParseException {
        this.node = node;
        this.packetSender = node.ps;
        // Read the rest from the FieldSet
        String loc = fs.get("location");
        currentLocation = new Location(loc);
        // FIXME: identity should be a PK
        String nodeID = fs.get("identity");
        nodeIdentity = HexUtil.hexToBytes(nodeID);
        String physical = fs.get("physical.udp");
        // Parse - FIXME use the old NodeReference code?
        // FIXME support multiple transports??
        peer = new Peer(physical);
        usm = node.usm;
        sentPacketsBySequenceNumber = new HashMap();
        ackQueue = new LinkedList();
        resendRequestQueue = new LinkedList();
        ackRequestQueue = new LinkedList();
        
        // FIXME!! Session key should be set up via PK negotiation
        // Hack here: sesskey = H(identity1) XOR H(identity2)
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new Error(e);
        }
        byte[] key = md.digest(nodeIdentity);
        byte[] okey = node.identityHash;
        for(int i=0;i<key.length;i++)
            key[i] ^= okey[i];
        //Logger.minor(this, "Session key: "+HexUtil.bytesToHex(key));
        try {
            sessionCipher = new Rijndael(256,128);
        } catch (UnsupportedCipherException e1) {
            throw new Error(e1);
        }
        sessionCipher.initialize(key);
        // FIXME: this is a debugging aid, maybe we should keep it?
        outgoingPacketNumber = node.random.nextInt(100000);
        //Logger.minor(this, "outgoingPacketNumber initialized to "+outgoingPacketNumber);
        // 10 extra hops on average at the start (for cover)
        decrementAtMax = node.random.nextDouble() <= 0.1;
        // 5 extra hops on average at the end (to prevent probing and similar attacks)
        decrementAtMin = node.random.nextDouble() <= 0.25;
    }

    public String toString() {
        // Excessive??
        return super.toString() + ": contact="+peer+", identity="+
        	HexUtil.bytesToHex(nodeIdentity)+", loc="+currentLocation.getValue()+
        	", cached: "+lowestSequenceNumberStillCached+"-"+
        	highestSequenceNumberStillCached+" ("+
        	sentPacketsBySequenceNumber.size()+"), packetsRecv: "+
        	packetsReceived+", ackQueue: "+ackQueue.size()+
        	", resendQueue: "+resendRequestQueue.size()+
        	", outgoingPacketNo: "+outgoingPacketNumber;
    }
    
    /** Keyspace location */
    private Location currentLocation;

    /** Node identity - for now just a block of data, like on Node.
     * Will later be a real public key. */
    private byte[] nodeIdentity;
    
    /** Contact information - FIXME should be a NodeReference??? */
    private Peer peer;
    
    private final UdpSocketManager usm;
    
    private final Node node;

    private final PacketSender packetSender;
    
    /**
     * Get the current Location, which represents our current 
     * specialization in the keyspace.
     */
    public Location getLocation() {
        return currentLocation;
    }

    /**
     * Get the Peer, the underlying TCP/IP address that UdpSocketManager
     * can understand.
     * @return
     */
    public Peer getPeer() {
        return peer;
    }

    /*
     * Stuff related to packet retransmission etc.
     * 
     * We need:
     * - A list of the message content of the last 256 packets we
     *   have sent. Every time we send a packet, we add to the
     *   end of the list. Every time we get an ack, we remove the
     *   relevant packet. Every time we get a retransmit request,
     *   we resend that packet. If packet N-255 has not yet been
     *   acked, we do not allow packet N to be sent.
     * - A function to determine whether packet N-255 has been
     *   acked, and a mutex to block on, in the prepare-to-send
     *   function.
     * - A list of packets that need to be ACKed.
     * - A list of packets that need to be resent by the other side.
     * - A list of packets that this side needs to resend.
     * - A function to determine the next time at which we need to
     *   check whether we need to send an empty packet just for the
     *   acks and resend requests.
     * - A thread to resend packets that were requested.
     * - A thread to send a packet with only acks and resend
     *   requests, should it be necessary (i.e. if they are older
     *   than 200ms).
     * 
     * For now, we don't support dropping messages in response to
     * OOM. But if we get a notification of a dropped message we
     * will stop trying to get it resent.
     */
    
    final HashMap sentPacketsBySequenceNumber;
    int lowestSequenceNumberStillCached = -1;
    int highestSequenceNumberStillCached = -1;
    
    /**
     * Called when we have sent a packet.
     * Adds it to the cache of unacknowledged packets.
     * @param messagesPayload The packet payload - the messages sent,
     * including the message lengths and the number of messages. 
     * Plaintext.
     * @param seqNumber The sequence number of the packet.
     */
    public synchronized void sentPacket(byte[] messagesPayload, int seqNumber) {
        Logger.minor(this, "sentPacket("+seqNumber);
        sentPacketsBySequenceNumber.put(new Integer(seqNumber), messagesPayload);
        if(seqNumber > highestSequenceNumberStillCached)
            highestSequenceNumberStillCached = seqNumber;
        if(lowestSequenceNumberStillCached < 0)
            lowestSequenceNumberStillCached = seqNumber;
        // Sometimes messages are sent slightly out of order
        if(seqNumber > 0 && lowestSequenceNumberStillCached > seqNumber)
            lowestSequenceNumberStillCached = seqNumber;
        // If not received ack in 500ms, send an AckRequest
        queueAckRequest(seqNumber, false);
    }

    /**
     * Resend a packet
     * @param packetNumber The sequence number of the packet to resend.
     */
    public void resendPacket(int packetNumber) {
        Integer i = new Integer(packetNumber);
        if(!sentPacketsBySequenceNumber.containsKey(i)) {
            Logger.error(this, "Cannot resend packet "+packetNumber+" on "+this);
            return;
        }
        byte[] payload = (byte[])sentPacketsBySequenceNumber.get(i);
        // Send it
        
        synchronized(packetSender) {
            packetSender.queueForImmediateSend(payload, packetNumber, this);
        }
    }
    
    /**
     * Called when we receive a packet acknowledgement.
     * Delete the packet from the cache, and update the upper
     * and lower sequence number bounds.
     * @param realSeqNo
     */
    public synchronized void acknowledgedPacket(int realSeqNo) {
        Logger.minor(this, "Ack received: "+realSeqNo);
        Integer i = new Integer(realSeqNo);
        removeAckRequest(realSeqNo);
        if(sentPacketsBySequenceNumber.containsKey(i)) {
            sentPacketsBySequenceNumber.remove(i);
            if(sentPacketsBySequenceNumber.size() == 0) {
                lowestSequenceNumberStillCached = -1;
                highestSequenceNumberStillCached = -1;
                notifyAll();
            } else {
                if(realSeqNo == lowestSequenceNumberStillCached) {
                    int origValue = lowestSequenceNumberStillCached;
                    while(!sentPacketsBySequenceNumber.containsKey(new Integer(lowestSequenceNumberStillCached))) {
                        lowestSequenceNumberStillCached++;
                        if(lowestSequenceNumberStillCached > origValue+256) {
                            Logger.error(this, "Inconsistent? lowestSequenceNumberStillCached was "+origValue+
                                    ", sentPackets.size()="+sentPacketsBySequenceNumber.size()+
                                    " but went through 256 indexes without finding cached value in ackPacket("+realSeqNo+")");
                            for(Iterator it=sentPacketsBySequenceNumber.keySet().iterator();it.hasNext();) {
                                Logger.error(this, "Got: "+it.next());
                            }
                        }
                    }
                    notifyAll();
                }
                if(realSeqNo == highestSequenceNumberStillCached) {
                    while(!sentPacketsBySequenceNumber.containsKey(new Integer(highestSequenceNumberStillCached))) {
                        highestSequenceNumberStillCached--;
                        if(highestSequenceNumberStillCached < (realSeqNo-512)) {
                            Logger.error(this, "Inconsistent? highestSequenceNumberStillCached was "+realSeqNo+
                                    ", sentPackets.size()="+sentPacketsBySequenceNumber.size()+
                                    " but went through 512 indexes without finding cached value in ackPacket("+realSeqNo+")");
                            for(Iterator it=sentPacketsBySequenceNumber.keySet().iterator();it.hasNext();) {
                                Logger.error(this, "Got: "+it.next());
                            }
                        }
                    }
                    notifyAll();
                }
                if(lowestSequenceNumberStillCached < 0 || highestSequenceNumberStillCached < 0)
                    throw new IllegalStateException();
            }
        }
    }
    
    ReceivedPackets packetsReceived = new ReceivedPackets();
    
    /**
     * @param seqNumber
     */
    public synchronized void receivedPacket(int seqNumber) {
        Logger.minor(this, "RECEIVED PACKET "+seqNumber);
        if(seqNumber == -1) return;
        // First ack it
        queueAck(seqNumber);
        receivedPacketNumber(seqNumber);
        // Resend requests
        removeResendRequest(seqNumber);
        packetsReceived.got(seqNumber);
    }

    /**
     * Add some resend requests if necessary
     */
    private synchronized void receivedPacketNumber(int seqNumber) {
        int max = packetsReceived.highest();
        if(seqNumber > max) {
            if(max != -1 && seqNumber - max > 1) {
                // Missed some packets out
                for(int i=max+1;i<seqNumber;i++) {
                    queueResendRequest(i);
                }
            }
        }
    }
    
    /** Packet numbers that need to be acknowledged by us.
     * In order of urgency. PAIs are removed from this list
     * when the ack is sent, and are added when we receive a 
     * packet.
     */
    final LinkedList ackQueue;
    
    class PacketActionItem { // anyone got a better name?
        /** Packet sequence number */
        int packetNumber;
        /** Time at which this packet's ack or resend request becomes urgent
         * and can trigger an otherwise empty packet to be sent. */
        long urgentTime;
    }
    
    class QueuedAck extends PacketActionItem {
        void sent() {
            ackQueue.remove(this);
        }
        
        QueuedAck(int packet) {
            long now = System.currentTimeMillis();
            packetNumber = packet;
            /** If not included on a packet in next 200ms, then
             * force a send of an otherwise empty packet.
             */
            urgentTime = now + 200;
        }
    }

    /**
     * Queue an acknowledgement. We will queue this for 200ms; if
     * any packet gets sent we will include all acknowledgements
     * on that packet. If after 200ms it is still queued, we will
     * send a packet just for the ack's.
     * @param packetNumber The packet number to acknowledge.
     */
    void queueAck(int packetNumber) {
        Logger.minor(this, "Queueing ack "+packetNumber);
        if(alreadyQueuedAck(packetNumber)) return;
        QueuedAck ack = new QueuedAck(packetNumber);
        // Oldest are first, youngest are last
        ackQueue.addLast(ack);
    }
    
    /**
     * Is a packet number already on the ack queue?
     */
    private boolean alreadyQueuedAck(int packetNumber) {
        for(Iterator i=ackQueue.iterator();i.hasNext();) {
            int seq = ((QueuedAck) i.next()).packetNumber;
            if(seq == packetNumber) return true;
        }
        return false;
    }

    abstract class BaseQueuedResend extends PacketActionItem {
        /** Time at which this item becomes sendable. Initially -1,
         * meaning it can be sent immediately. When we send a 
         * resend request, this is reset to t+500ms.
         * 
         * Constraint: urgentTime is always greater than activeTime.
         */
        long activeTime;
        
        void sent() {
            long now = System.currentTimeMillis();
            activeTime = now + 500;
            urgentTime = activeTime + 200;
            // This is only removed when we actually receive the packet
            // But for now it will sleep
            // Don't need to change position in list as it won't change as we send all of them at once
        }
        
        BaseQueuedResend(int packetNumber) {
            this.packetNumber = packetNumber;
            long now = System.currentTimeMillis();
            activeTime = initialActiveTime(); // active immediately
            urgentTime = activeTime + 200; // urgent in 200ms
        }
        
        abstract long initialActiveTime();
        
    }
    
    class QueuedResendRequest extends BaseQueuedResend {
        long initialActiveTime() {
            // Active in 200ms - might have been sent out of order
            return System.currentTimeMillis() + 200;
        }
        
        QueuedResendRequest(int packetNumber) {
            super(packetNumber);
        }
    }
    
    class QueuedAckRequest extends BaseQueuedResend {
        long initialActiveTime() {
            // 500ms after sending packet, send ackrequest
            return System.currentTimeMillis() + 500;
        }
        
        QueuedAckRequest(int packetNumber, boolean sendSoon) {
            super(packetNumber);
            if(sendSoon) {
                activeTime -= 500;
                urgentTime -= 500;
            }
        }
    }
    
    /** Packet numbers we need to ask to be resent.
     * In order of urgency. PAIs are added when we receive a
     * packet with a sequence number greater than one we have not
     * yet received. PAIs are removed when we receive the packet.
     */
    final LinkedList resendRequestQueue;

    int lastResendRequestSeqNumber = -1;
    
    /**
     * Add a request for a packet to be resent to the queue.
     * @param seqNumber The packet number of the packet that needs
     * to be resent.
     */
    private synchronized void queueResendRequest(int seqNumber) {
        if(lastResendRequestSeqNumber < seqNumber)
            lastResendRequestSeqNumber = seqNumber;
        Logger.minor(this, "Queueing resend request for "+seqNumber+" on "+this);
        if(queuedResendRequest(seqNumber)) return;
        QueuedResendRequest qr = new QueuedResendRequest(seqNumber);
        // Add to queue in the right place
        
        if(resendRequestQueue.isEmpty())
            resendRequestQueue.addLast(qr);
        else {
            long lastUrgentTime = Long.MAX_VALUE;
            for(ListIterator i = resendRequestQueue.listIterator(resendRequestQueue.size());i.hasPrevious();) {
                QueuedResendRequest q = (QueuedResendRequest) (i.previous());
                long thisUrgentTime = q.urgentTime;
                if(thisUrgentTime > lastUrgentTime)
                    throw new IllegalStateException("Inconsistent resendRequestQueue: urgentTime increasing! on "+this);
                lastUrgentTime = thisUrgentTime;
                if(thisUrgentTime < qr.urgentTime) {
                    i.add(qr);
                    return;
                }
            }
            resendRequestQueue.addFirst(qr);
        }
        // We do not need to notify the subthread as it will never
        // sleep for more than 200ms, so it will pick up on the
        // need to send something then.
    }

    /**
     * Remove a queued request for a packet to be resent.
     * @param seqNumber The packet number of the packet that needs
     * to be resent.
     */
    private synchronized void removeResendRequest(int seqNumber) {
        for(ListIterator i=resendRequestQueue.listIterator();i.hasNext();) {
            QueuedResendRequest q = (QueuedResendRequest) (i.next());
            if(q.packetNumber == seqNumber) {
                i.remove();
            }
        }
    }

    private synchronized boolean queuedResendRequest(int seqNumber) {
        for(ListIterator i=resendRequestQueue.listIterator();i.hasNext();) {
            QueuedResendRequest q = (QueuedResendRequest) (i.next());
            if(q.packetNumber == seqNumber) {
                return true;
            }
        }
        return false;
    }
    
    // AckRequests
    
    /**
     * In order of urgency. QueuedAckRequest's are added when we send
     * a packet, and removed when it is acked.
     */
    final LinkedList ackRequestQueue;
    
    private synchronized void queueAckRequest(int seqNumber, boolean sendSoon) {
        Logger.minor(this, "Queueing ack request: "+seqNumber);
        if(queuedAckRequest(seqNumber)) return;
        QueuedAckRequest qa = new QueuedAckRequest(seqNumber, sendSoon);
        // Add to queue in the right place
        
        if(ackRequestQueue.isEmpty())
            ackRequestQueue.addLast(qa);
        else {
            long lastUrgentTime = Long.MAX_VALUE;
            for(ListIterator i = ackRequestQueue.listIterator(ackRequestQueue.size());i.hasPrevious();) {
                QueuedAckRequest q = (QueuedAckRequest) (i.previous());
                long thisUrgentTime = q.urgentTime;
                if(thisUrgentTime > lastUrgentTime)
                    throw new IllegalStateException("Inconsistent ackRequestQueue: urgentTime increasing! on "+this);
                lastUrgentTime = thisUrgentTime;
                if(thisUrgentTime < qa.urgentTime) {
                    i.add(qa);
                    return;
                }
            }
            ackRequestQueue.addFirst(qa);
        }
        // We do not need to notify the subthread as it will never
        // sleep for more than 200ms, so it will pick up on the
        // need to send something then.
    }
    
    private synchronized void removeAckRequest(int seqNumber) {
        for(ListIterator i=ackRequestQueue.listIterator();i.hasNext();) {
            QueuedAckRequest q = (QueuedAckRequest) (i.next());
            if(q.packetNumber == seqNumber) {
                i.remove();
            }
        }
    }

    private synchronized boolean queuedAckRequest(int seqNumber) {
        for(ListIterator i=ackRequestQueue.listIterator();i.hasNext();) {
            QueuedAckRequest q = (QueuedAckRequest) (i.next());
            if(q.packetNumber == seqNumber) {
                return true;
            }
        }
        return false;
    }
    
    
    
    
    BlockCipher sessionCipher;
    
    /**
     * Get the session key
     */
    public BlockCipher getSessionCipher() {
        return sessionCipher;
    }

    /**
     * @return The highest sequence number of all packets we have
     * received so far.
     */
    public int lastReceivedSequenceNumber() {
        return Math.max(packetsReceived.highest(), lastResendRequestSeqNumber);
    }

    /**
     * @return
     */
    public synchronized long getNextUrgentTime() {
        long nextUrgentTime = Long.MAX_VALUE;
        // Two queues to consider
        if(!ackQueue.isEmpty()) {
            PacketActionItem item = (PacketActionItem) ackQueue.getFirst();
            if(item == null)
                throw new NullPointerException("item is null from ackQueue which has "+ackQueue.size()+" elements");
            long urgentTime = item.urgentTime;
            if(urgentTime < nextUrgentTime)
                nextUrgentTime = urgentTime;
        }
        if(!resendRequestQueue.isEmpty()) {
            PacketActionItem item = (PacketActionItem) resendRequestQueue.getFirst();
            if(item == null)
                throw new NullPointerException("item is null from resendRequestQueue which has "+resendRequestQueue.size()+" elements");
            long urgentTime = item.urgentTime;
            if(urgentTime < nextUrgentTime)
                nextUrgentTime = urgentTime;
        }
        if(!ackRequestQueue.isEmpty()) {
            PacketActionItem item = (PacketActionItem) ackRequestQueue.getFirst();
            if(item == null)
                throw new NullPointerException("item is null from ackRequestQueue which has "+ackRequestQueue.size()+" elements");
            long urgentTime = item.urgentTime;
            if(urgentTime < nextUrgentTime)
                nextUrgentTime = urgentTime;
        }
        return nextUrgentTime;
    }

    /**
     * Destination forgot a packet we wanted it to resend.
     * Grrr!
     * @param realSeqNo
     */
    public void destForgotPacket(int seqNo) {
        Logger.normal(this, "Destination forgot packet: "+seqNo);
        removeResendRequest(seqNo);
    }

    // FIXME: this is just here to make it easy to debug
    int outgoingPacketNumber;
    
    /**
     * Allocate an outgoing packet number.
     * Block if necessary to keep it inside the sliding window.
     * We cannot send packet 256 until packet 0 has been ACKed.
     */
    public synchronized int allocateOutgoingPacketNumber() {
        int thisPacketNumber = outgoingPacketNumber++;
        	
        if(lowestSequenceNumberStillCached > 0 && 
                thisPacketNumber - lowestSequenceNumberStillCached >= 256) {
            Logger.normal(this, "Blocking until receive ack for packet "+
                    lowestSequenceNumberStillCached+" on "+this);
            while(lowestSequenceNumberStillCached > 0 &&
                    thisPacketNumber - lowestSequenceNumberStillCached >= 256) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    // Ignore
                }
            }
        }
        return thisPacketNumber;
    }

    /**
     * Remove all acks from the queue and return their packet numbers
     * as an array.
     * @return
     */
    public synchronized int[] grabAcks() {
        int[] acks = new int[ackQueue.size()];
        int x = 0;
        for(Iterator i=ackQueue.iterator();i.hasNext();) {
            QueuedAck qa = (QueuedAck) (i.next());
            int packetNumber = qa.packetNumber;
            acks[x++] = packetNumber;
        }
        ackQueue.clear();
        return acks;
    }

    /**
     * Get all active pending resend requests from the queue, and 
     * update their active and urgent times.
     * @return
     */
    public synchronized int[] grabResendRequests() {
        // Only return them if they are urgent
        int[] temp = new int[resendRequestQueue.size()];
        int x = 0;
        long now = System.currentTimeMillis();
        for(Iterator i=resendRequestQueue.iterator();i.hasNext();) {
            QueuedResendRequest qa = (QueuedResendRequest) (i.next());
            if(qa.activeTime > now) continue;
            int packetNumber = qa.packetNumber;
            temp[x++] = packetNumber;
            qa.sent();
        }
        int[] reqs = new int[x];
        System.arraycopy(temp, 0, reqs, 0, x);
        return reqs;
    }


    /**
     * Get all active pending ack requests from the queue, and
     * update their active and urgent times.
     * @return
     */
    public int[] grabAckRequests() {
        // Only return them if they are urgent
        int[] temp = new int[ackRequestQueue.size()];
        int x = 0;
        long now = System.currentTimeMillis();
        for(Iterator i=ackRequestQueue.iterator();i.hasNext();) {
            QueuedAckRequest qa = (QueuedAckRequest) (i.next());
            if(qa.activeTime > now) continue;
            int packetNumber = qa.packetNumber;
            temp[x++] = packetNumber;
            qa.sent();
        }
        int[] reqs = new int[x];
        System.arraycopy(temp, 0, reqs, 0, x);
        return reqs;
    }
    
    /**
     * IP on the other side appears to have changed...
     * @param peer2
     */
    public void changedIP(Peer peer2) {
        peer = peer2;
    }

    /**
     * Ping this node.
     * @return True if we received a reply inside 2000ms.
     * (If we have heavy packet loss, it can take that long to resend).
     */
    public boolean ping(int pingID) {
        Message ping = DMT.createFNPPing(pingID);
        usm.send(this, ping);
        Message msg = 
            usm.waitFor(MessageFilter.create().setTimeout(2000).setType(DMT.FNPPong).setField(DMT.PING_SEQNO, pingID));
        return msg != null;
    }

    /**
     * Send a Message immediately but off-thread.
     * @param reply The Message to send.
     * @param target The node to send it to.
     */
    public void sendAsync(Message reply) {
        byte[] buf = node.packetMangler.preformat(reply, this);
        packetSender.queueForImmediateSend(buf, -1, this);
    }

    /**
     * If we receive an AckRequest, we should send the ack again.
     * That is, if we sent it in the first place...
     * @param realSeqNo The packet number.
     *
     */
    public synchronized void receivedAckRequest(int realSeqNo) {
        // Did we send the ack in the first place?
        if(realSeqNo > lastReceivedSequenceNumber()) {
            if(realSeqNo - lastReceivedSequenceNumber() < 256
                    || lastReceivedSequenceNumber() == -1) {
                // They sent it, haven't had an ack yet
                queueResendRequest(realSeqNo);
                // Resend any in between too, and update lastReceivePacketSeqNumber
                receivedPacketNumber(realSeqNo);
            } else {
                Logger.error(this,"Other side asked for re-ack on "+realSeqNo+
                        " but last sent was "+lastReceivedSequenceNumber());
            }
            return;
        }
        if(queuedResendRequest(realSeqNo)) {
            // We will get them to resend it first
            Logger.minor(this, "Other side asked for re-ack on "+realSeqNo+
                    " but we haven't received it yet");
            return;
        }
        queueAck(realSeqNo);
    }

    public synchronized int getLastOutgoingSeqNumber() {
        return outgoingPacketNumber;
    }

    public void updateLocation(double newLoc) {
        currentLocation.setValue(newLoc);
    }

    public byte[] getNodeIdentity() {
        return nodeIdentity;
    }

    RunningAverage swapRequestTimes =
        new TimeDecayingRunningAverage(1000.0, 600*1000, 0, 600*1000);
    
    long lastRejectedSwapRequest = -1;

    // 900ms
    static final long MIN_INTERVAL_BETWEEN_SWAP_REQUESTS = 900;
    
    public synchronized boolean shouldRejectSwapRequest() {
        long now = System.currentTimeMillis();
        if(lastRejectedSwapRequest > 0) {
            long timeSinceLastTime = now - lastRejectedSwapRequest;
            swapRequestTimes.report(timeSinceLastTime);
            double averageInterval = swapRequestTimes.currentValue();
            if(averageInterval < MIN_INTERVAL_BETWEEN_SWAP_REQUESTS) {
                double p = 
                    (MIN_INTERVAL_BETWEEN_SWAP_REQUESTS - averageInterval) /
                    MIN_INTERVAL_BETWEEN_SWAP_REQUESTS;
                return node.random.nextDouble() < p;
            } else return false;
                
        }
        lastRejectedSwapRequest = now;
        return false;
    }

    /** Should we decrement HTL when it is 1? This is set once per node
     * because otherwise correlation attacks are much easier.
     */
    final boolean decrementAtMin;
    
    /**
     * Should we decrement HTL when it is at the maximum?
     */
    final boolean decrementAtMax;
    
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
            if(decrementAtMax) htl--;
            return htl;
        }
        if(htl == 1) {
            if(decrementAtMin) htl--;
            return htl;
        }
        htl--;
        return htl;
    }

    /**
     * Have we received a packet with this sequence number from
     * this peer before?
     * @param seqNumber The packet number to check.
     * @return True if we have already received a packet with this
     * sequence number.
     */
    public boolean alreadyReceived(int seqNumber) {
        return packetsReceived.contains(seqNumber);
    }
}
