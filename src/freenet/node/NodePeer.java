package freenet.node;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.ListIterator;

import freenet.crypt.BlockCipher;
import freenet.crypt.ciphers.Rijndael;
import freenet.io.comm.Peer;
import freenet.io.comm.PeerContext;
import freenet.io.comm.UdpSocketManager;
import freenet.support.Logger;

/**
 * @author amphibian
 * 
 * Another node.
 */
public class NodePeer implements PeerContext {
    
    NodePeer(Location loc, Peer contact, byte[] nodeIdentity, Node n, PacketSender ps) {
        currentLocation = loc;
        peer = contact;
        sentPacketsBySequenceNumber = new HashMap();
        ackQueue = new LinkedList();
        resendRequestQueue = new LinkedList();
        node = n;
        usm = node.usm;
        // FIXME!! Session key should be set up via PK negotiation
        // (This code here is a hack, it does not provide any real
        // security)!
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new Error(e);
        }
        this.nodeIdentity = nodeIdentity;
        byte[] key = md.digest(nodeIdentity);
        sessionCipher = new Rijndael();
        sessionCipher.initialize(key);
        this.packetSender = ps;
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
        sentPacketsBySequenceNumber.put(new Integer(seqNumber), messagesPayload);
        if(seqNumber > highestSequenceNumberStillCached)
            highestSequenceNumberStillCached = seqNumber;
        if(lowestSequenceNumberStillCached < 0)
            lowestSequenceNumberStillCached = seqNumber;
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
            packetSender.queueForImmediateSend(payload, this);
        }
    }
    
    /**
     * Called when we receive a packet acknowledgement.
     * Delete the packet from the cache, and update the upper
     * and lower sequence number bounds.
     * @param realSeqNo
     */
    public synchronized void acknowledgedPacket(int realSeqNo) {
        Integer i = new Integer(realSeqNo);
        if(sentPacketsBySequenceNumber.containsKey(i)) {
            sentPacketsBySequenceNumber.remove(i);
            if(sentPacketsBySequenceNumber.size() == 0) {
                lowestSequenceNumberStillCached = -1;
                highestSequenceNumberStillCached = -1;
            } else {
                if(realSeqNo == lowestSequenceNumberStillCached) {
                    while(!sentPacketsBySequenceNumber.containsKey(new Integer(lowestSequenceNumberStillCached)))
                        lowestSequenceNumberStillCached++;
                }
                if(realSeqNo == highestSequenceNumberStillCached) {
                    while(!sentPacketsBySequenceNumber.containsKey(new Integer(highestSequenceNumberStillCached)))
                        highestSequenceNumberStillCached--;
                }
                if(lowestSequenceNumberStillCached < 0 || highestSequenceNumberStillCached < 0)
                    throw new IllegalStateException();
            }
        }
    }
    
    /**
     * Sequence number of last received packet, not including
     * retransmitted packets.
     */
    int lastReceivedPacketSeqNumber = -1;

    /**
     * @param seqNumber
     */
    public synchronized void receivedPacket(int seqNumber) {
        if(seqNumber > lastReceivedPacketSeqNumber)
            lastReceivedPacketSeqNumber = seqNumber;
        // First ack it
        queueAck(seqNumber);
        // Resend requests
        if(seqNumber < lastReceivedPacketSeqNumber) {
            removeResendRequest(seqNumber);
        } else {
            int oldSeqNo = lastReceivedPacketSeqNumber;
            lastReceivedPacketSeqNumber = seqNumber;
            if(seqNumber - oldSeqNo > 1) {
                // Missed some packets out
                for(int i=oldSeqNo;i<seqNumber;i++) {
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
    
    /** Time at which the first queued packet will become urgent. */
    long nextUrgentTime = -1;

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
    private void queueAck(int packetNumber) {
        if(alreadyQueuedAck(packetNumber)) return;
        QueuedAck ack = new QueuedAck(packetNumber);
        // Oldest are first, youngest are last
        if(ackQueue.isEmpty()) {
            if(nextUrgentTime < 0)
                nextUrgentTime = ack.urgentTime;
            else
                nextUrgentTime = Math.min(ack.urgentTime, nextUrgentTime);
        }
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

    class QueuedResend extends PacketActionItem {
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
            // Reset to back of list
            // This is only removed when we actually receive the packet
            // But for now it will sleep
            resendRequestQueue.remove(this);
            resendRequestQueue.addLast(this);
        }
        
        QueuedResend(int packetNumber) {
            this.packetNumber = packetNumber;
            long now = System.currentTimeMillis();
            activeTime = -1; // active immediately
            urgentTime = now + 200; // urgent in 200ms
        }
    }
    
    /** Packet numbers we need to ask to be resent.
     * In order of urgency. PAIs are added when we receive a
     * packet with a sequence number greater than one we have not
     * yet received. PAIs are removed when we receive the packet.
     */
    final LinkedList resendRequestQueue;

    /**
     * Add a request for a packet to be resent to the queue.
     * @param seqNumber The packet number of the packet that needs
     * to be resent.
     */
    private synchronized void queueResendRequest(int seqNumber) {
        QueuedResend qr = new QueuedResend(seqNumber);
        // Add to queue in the right place
        
        if(resendRequestQueue.isEmpty())
            resendRequestQueue.addLast(qr);
        else {
            long lastUrgentTime = Long.MAX_VALUE;
            for(ListIterator i = resendRequestQueue.listIterator(resendRequestQueue.size());i.hasPrevious();) {
                QueuedResend q = (QueuedResend) (i.previous());
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
            QueuedResend q = (QueuedResend) (i.next());
            if(q.packetNumber == seqNumber) {
                i.remove();
            }
        }
    }

    // FIXME: this needs to be set somewhere
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
        return lastReceivedPacketSeqNumber;
    }

}
