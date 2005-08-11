package freenet.node;

import java.util.Enumeration;
import java.util.HashSet;

import freenet.crypt.BlockCipher;
import freenet.io.comm.NotConnectedException;
import freenet.support.DoublyLinkedList;
import freenet.support.DoublyLinkedListImpl;
import freenet.support.IndexableUpdatableSortedLinkedListItem;
import freenet.support.LimitedRangeIntByteArrayMap;
import freenet.support.Logger;
import freenet.support.UpdatableSortedLinkedListItem;
import freenet.support.UpdatableSortedLinkedListWithForeignIndex;
import freenet.support.WouldBlockException;
import freenet.support.DoublyLinkedList.Item;

/**
 * @author amphibian
 * 
 * Class to track everything related to a single key on a single
 * PeerNode. In particular, the key itself, packets sent and
 * received, and packet numbers.
 */
public class KeyTracker {

    /** Parent PeerNode */
    public final PeerNode pn;
    
    /** Are we the secondary key? */
    private boolean isDeprecated;
    
    /** Cipher to both encrypt outgoing packets with and decrypt
     * incoming ones. */
    public final BlockCipher sessionCipher;
    
    /** Packets we have sent to the node, minus those that have
     * been acknowledged. */
    private final LimitedRangeIntByteArrayMap sentPacketsContents;
    
    /** Serial numbers of packets that we want to acknowledge,
     * and when they become urgent. We always add to the end,
     * and we always remove from the beginning, so should always
     * be consistent. */
    private final DoublyLinkedList ackQueue;
    
    /** The highest incoming serial number we have ever seen
     * from the other side. Includes actual packets and resend
     * requests (provided they are within range). */
    private int highestSeenIncomingSerialNumber;
    
    /** Serial numbers of packets we want to be resent by the
     * other side to us, the time at which they become sendable,
     * and the time at which they become urgent. In order of
     * the latter. */
    private final UpdatableSortedLinkedListWithForeignIndex resendRequestQueue;
    
    /** Serial numbers of packets we want to be acknowledged by
     * the other side, the time at which they become sendable,
     * and the time at which they become urgent. In order of
     * the latter. */
    private final UpdatableSortedLinkedListWithForeignIndex ackRequestQueue;
    
    /** Numbered packets that we need to send to the other side
     * because they asked for them. */
    private final HashSet packetsToResend;
    
    /** Ranges of packet numbers we have received from the other
     * side. */
    private final ReceivedPacketNumbers packetNumbersReceived;
    
    /** Counter to generate the next packet number */
    private int nextPacketNumber;
    
    /** Everything is clear to start with */
    KeyTracker(PeerNode pn, BlockCipher cipher) {
        this.pn = pn;
        this.sessionCipher = cipher;
        ackQueue = new DoublyLinkedListImpl();
        highestSeenIncomingSerialNumber = -1;
        sentPacketsContents = new LimitedRangeIntByteArrayMap(256);
        resendRequestQueue = new UpdatableSortedLinkedListWithForeignIndex();
        ackRequestQueue = new UpdatableSortedLinkedListWithForeignIndex();
        packetsToResend = new HashSet();
        packetNumbersReceived = new ReceivedPacketNumbers(512);
        isDeprecated = false;
    }

    /**
     * Set the deprecated flag to indicate that we are now
     * no longer the primary key. And wake up any threads trying to lock
     * a packet number; they can be sent with the new KT.
     */
    public void deprecated() {
        isDeprecated = true;
        sentPacketsContents.interrupt();
    }
    
    /**
     * @return The highest received incoming serial number.
     */
    public int highestReceivedIncomingSeqNumber() {
        return this.highestSeenIncomingSerialNumber;
    }

    /**
     * Received this packet??
     */
    public boolean alreadyReceived(int seqNumber) {
        return packetNumbersReceived.contains(seqNumber);
    }
    
    /** toString() - don't leak the key unless asked to */
    public String toString() {
        return super.toString()+" for "+pn.shortToString();
    }

    /**
     * Queue an ack to be sent back to the node soon.
     * @param seqNumber The number of the packet to be acked.
     */
    public void queueAck(int seqNumber) {
        Logger.minor(this, "Queueing ack for "+seqNumber);
        QueuedAck qa = new QueuedAck(seqNumber);
        synchronized(ackQueue) {
            ackQueue.push(qa);
        }
        // Will go urgent in 200ms
    }
    
    class PacketActionItem { // anyone got a better name?
        /** Packet sequence number */
        int packetNumber;
        /** Time at which this packet's ack or resend request becomes urgent
         * and can trigger an otherwise empty packet to be sent. */
        long urgentTime;
        
        public String toString() {
            return super.toString()+": packet "+packetNumber+" urgent@"+urgentTime;
        }
    }
    
    class QueuedAck extends PacketActionItem implements DoublyLinkedList.Item {
        void sent() {
            synchronized(ackQueue) {
                ackQueue.remove(this);
            }
        }
        
        QueuedAck(int packet) {
            long now = System.currentTimeMillis();
            packetNumber = packet;
            /** If not included on a packet in next 200ms, then
             * force a send of an otherwise empty packet.
             */
            urgentTime = now + 200;
        }

        Item prev;
        Item next;
        
        public Item getNext() {
            return next;
        }

        public Item setNext(Item i) {
            Item old = next;
            next = i;
            return old;
        }

        public Item getPrev() {
            return prev;
        }

        public Item setPrev(Item i) {
            Item old = prev;
            prev = i;
            return old;
        }
    }

    private abstract class BaseQueuedResend extends PacketActionItem implements IndexableUpdatableSortedLinkedListItem {
        /** Time at which this item becomes sendable.
         * When we send a resend request, this is reset to t+500ms.
         * 
         * Constraint: urgentTime is always greater than activeTime.
         */
        long activeTime;
        final Integer packetNumberAsInteger;
        
        void sent() {
            long now = System.currentTimeMillis();
            activeTime = now + 500;
            urgentTime = activeTime + 200;
            // This is only removed when we actually receive the packet
            // But for now it will sleep
        }
        
        BaseQueuedResend(int packetNumber) {
            this.packetNumber = packetNumber;
            packetNumberAsInteger = new Integer(packetNumber);
            long now = System.currentTimeMillis();
            activeTime = initialActiveTime(now); // active immediately
            urgentTime = activeTime + 200; // urgent in 200ms
        }
        
        abstract long initialActiveTime(long now);

        private Item next;
        private Item prev;
        
        public final Item getNext() {
            return next;
        }

        public final Item setNext(Item i) {
            Item old = next;
            next = i;
            return old;
        }

        public Item getPrev() {
            return prev;
        }

        public Item setPrev(Item i) {
            Item old = prev;
            prev = i;
            return old;
        }

        public int compareTo(Object o) {
            BaseQueuedResend r = (BaseQueuedResend)o;
            if(urgentTime > r.urgentTime) return 1;
            if(urgentTime < r.urgentTime) return -1;
            return 0;
        }
        
        public Object indexValue() {
            return packetNumberAsInteger;
        }
    }
    
    private class QueuedResendRequest extends BaseQueuedResend {
        long initialActiveTime(long now) {
            // Active in 200ms - might have been sent out of order
            return now + 200;
        }
        
        QueuedResendRequest(int packetNumber) {
            super(packetNumber);
        }
        
        void sent() {
            synchronized(resendRequestQueue) {
                resendRequestQueue.update(this);
            }
            super.sent();
        }
    }
    
    private class QueuedAckRequest extends BaseQueuedResend {
        long initialActiveTime(long now) {
            // 500ms after sending packet, send ackrequest
            return now + 500;
        }
        
        QueuedAckRequest(int packetNumber, boolean sendSoon) {
            super(packetNumber);
            if(sendSoon) {
                activeTime -= 500;
                urgentTime -= 500;
            }
        }
        
        void sent() {
            synchronized(ackRequestQueue) {
                ackRequestQueue.update(this);
            }
            super.sent();
        }
    }
    
    /**
     * Called when we receive a packet.
     * @param seqNumber The packet's serial number.
     */
    public synchronized void receivedPacket(int seqNumber) {
        pn.receivedPacket();
        if(seqNumber == -1) return;
        receivedPacketNumber(seqNumber);
        if(packetNumbersReceived.contains(seqNumber)) {
            // They resent it
            // Lets re-ack it.
            queueAck(seqNumber);
        } else {
            receivedPacketNumber(seqNumber);
            queueAck(seqNumber);
        }
    }

    protected synchronized void receivedPacketNumber(int seqNumber) {
        queueResendRequests(seqNumber);
        packetNumbersReceived.got(seqNumber);
        removeResendRequest(seqNumber);
        highestSeenIncomingSerialNumber = Math.max(highestSeenIncomingSerialNumber, seqNumber);
    }
    
    /**
     * Remove a resend request from the queue.
     * @param seqNumber
     */
    private void removeResendRequest(int seqNumber) {
        resendRequestQueue.removeByKey(new Integer(seqNumber));
    }

    /**
     * Add some resend requests if necessary.
     * @param seqNumber The number of the packet we just received.
     */
    private synchronized void queueResendRequests(int seqNumber) {
        int max = packetNumbersReceived.highest();
        if(seqNumber > max) {
            if(max != -1 && seqNumber - max > 1) {
                // Missed some packets out
                for(int i=max+1;i<seqNumber;i++) {
                    queueResendRequest(i);
                }
            }
        }
    }

    /**
     * Queue a resend request
     * @param packetNumber the packet serial number to queue a
     * resend request for
     */
    private void queueResendRequest(int packetNumber) {
        if(queuedResendRequest(packetNumber)) {
            Logger.minor(this, "Not queueing resend request for "+packetNumber+" - already queued");
            return;
        }
        QueuedResendRequest qrr = new QueuedResendRequest(packetNumber);
        resendRequestQueue.add(qrr);
    }

    /**
     * Is a resend request queued for this packet number?
     */
    private boolean queuedResendRequest(int packetNumber) {
        return resendRequestQueue.containsKey(new Integer(packetNumber));
    }

    /**
     * Called when we have received a packet acknowledgement.
     * @param realSeqNo
     */
    public void acknowledgedPacket(int realSeqNo) {
        removeAckRequest(realSeqNo);
        sentPacketsContents.remove(realSeqNo);
    }

    /**
     * Remove an ack request from the queue by packet number.
     */
    private void removeAckRequest(int seqNo) {
        ackRequestQueue.removeByKey(new Integer(seqNo));
    }

    /**
     * Resend (off-thread but ASAP) a packet.
     * @param seqNumber The serial number of the packet to be
     * resent.
     */
    public void resendPacket(int seqNumber) {
        byte[] resendData = sentPacketsContents.get(seqNumber);
        if(resendData != null) {
            pn.node.ps.queueResendPacket(resendData, seqNumber, this);
        } else {
            Logger.error(this, "Asking me to resend packet "+seqNumber+
                    " which we haven't sent yet or which they have already acked");
        }
    }

    /**
     * Called when we receive an AckRequest.
     * @param packetNumber The packet that the other side wants
     * us to re-ack.
     */
    public void receivedAckRequest(int packetNumber) {
        if(queuedAck(packetNumber)) {
            // Already going to send an ack
            // Don't speed it up though; wasteful
        } else {
            if(packetNumbersReceived.contains(packetNumber)) {
                // We have received it, so send them an ack
                queueAck(packetNumber);
            } else {
                // We have not received it, so get them to resend it
                queueResendRequest(packetNumber);
                synchronized(this) {
                    highestSeenIncomingSerialNumber = Math.max(highestSeenIncomingSerialNumber, packetNumber);
                }
            }
        }
    }

    /**
     * Is there a queued ack with the given packet number?
     * FIXME: have a hashtable? The others do, but probably it
     * isn't necessary. We should be consistent about this -
     * either take it out of UpdatableSortedLinkedListWithForeignIndex,
     * or add one here.
     */
    private boolean queuedAck(int packetNumber) {
        synchronized(ackQueue) {
            for(Enumeration e=ackQueue.elements();e.hasMoreElements();) {
                QueuedAck qa = (QueuedAck) e.nextElement();
                if(qa.packetNumber == packetNumber) return true;
            }
        }
        return false;
    }

    /**
     * Destination forgot a packet.
     * This is normal if we are the secondary key.
     * @param seqNumber The packet number lost.
     */
    public void destForgotPacket(int seqNumber) {
        if(isDeprecated) {
            Logger.normal(this, "Destination forgot packet: "+seqNumber);
        } else {
            Logger.error(this, "Destination forgot packet: "+seqNumber);
        }
        removeResendRequest(seqNumber);
    }

    /**
     * @return A packet number for a new outgoing packet.
     * This method will block until one is available if
     * necessary.
     * @throws KeyChangedException if the thread is interrupted when waiting
     */
    public int allocateOutgoingPacketNumber() throws KeyChangedException, NotConnectedException {
        int packetNumber;
        if(!pn.isConnected()) throw new NotConnectedException();
        synchronized(this) {
            packetNumber = nextPacketNumber++;
        }
        if(isDeprecated) throw new KeyChangedException();
        while(true) {
            try {
                sentPacketsContents.lock(packetNumber);
                return packetNumber;
            } catch (InterruptedException e) {
                if(isDeprecated) throw new KeyChangedException();
            }
        }
    }

    /**
     * @return A packet number for a new outgoing packet.
     * This method will not block, and will throw an exception
     * if it would need to block.
     * @throws KeyChangedException if the thread is interrupted when waiting
     */
    public int allocateOutgoingPacketNumberNeverBlock() throws KeyChangedException, NotConnectedException, WouldBlockException {
        int packetNumber;
        if(!pn.isConnected()) throw new NotConnectedException();
        synchronized(this) {
            packetNumber = nextPacketNumber+1;
            if(isDeprecated) throw new KeyChangedException();
            sentPacketsContents.lockNeverBlock(packetNumber);
            nextPacketNumber = packetNumber;
            return packetNumber;
        }
    }

    /**
     * Grab all the currently queued acks to be sent to this node.
     * @return An array of packet numbers that we need to acknowledge.
     */
    public int[] grabAcks() {
        int[] acks;
        synchronized(ackQueue) {
            // Grab the acks and tell them they are sent
            int length = ackQueue.size();
            acks = new int[length];
            int i=0;
            for(Enumeration e=ackQueue.elements();e.hasMoreElements();) {
                QueuedAck ack = (QueuedAck)e.nextElement();
                acks[i++] = ack.packetNumber;
                Logger.minor(this, "Grabbing ack "+ack.packetNumber);
                ack.sent();
            }
        }
        return acks;
    }

    /**
     * Grab all the currently queued resend requests.
     * @return An array of the packet numbers of all the packets we want to be resent.
     */
    public int[] grabResendRequests() {
        UpdatableSortedLinkedListItem[] items;
        int[] packetNumbers;
        int realLength;
        long now = System.currentTimeMillis();
        synchronized(resendRequestQueue) {
            items = resendRequestQueue.toArray();
            int length = items.length;
            packetNumbers = new int[length];
            realLength = 0;
            for(int i=0;i<length;i++) {
                QueuedResendRequest qrr = (QueuedResendRequest)items[i];
                if(qrr.activeTime > now) {
                    packetNumbers[realLength++] = qrr.packetNumber;
                    qrr.sent();
                }
            }
        }
        int[] trimmedPacketNumbers = new int[realLength];
        System.arraycopy(packetNumbers, 0, trimmedPacketNumbers, 0, realLength);
        return trimmedPacketNumbers;
    }

    /**
     * @return
     */
    public int[] grabAckRequests() {
        UpdatableSortedLinkedListItem[] items;
        int[] packetNumbers;
        int realLength;
        long now = System.currentTimeMillis();
        synchronized(ackRequestQueue) {
            items = ackRequestQueue.toArray();
            int length = items.length;
            packetNumbers = new int[length];
            realLength = 0;
            for(int i=0;i<length;i++) {
                QueuedAckRequest qrr = (QueuedAckRequest)items[i];
                if(qrr.activeTime > now) {
                    packetNumbers[realLength++] = qrr.packetNumber;
                    qrr.sent();
                }
            }
        }
        int[] trimmedPacketNumbers = new int[realLength];
        System.arraycopy(packetNumbers, 0, trimmedPacketNumbers, 0, realLength);
        return trimmedPacketNumbers;
    }

    /**
     * @return The time at which we will have to send some
     * notifications. Or Long.MAX_VALUE if there are none to send.
     */
    public long getNextUrgentTime() {
        long earliestTime = Long.MAX_VALUE;
        synchronized(ackQueue) {
            if(!ackQueue.isEmpty()) {
                QueuedAck qa = (QueuedAck)ackQueue.head();
                earliestTime = qa.urgentTime;
            }
        }
        synchronized(resendRequestQueue) {
            if(!resendRequestQueue.isEmpty()) {
                QueuedResendRequest qr = (QueuedResendRequest) resendRequestQueue.getLowest();
                earliestTime = Math.min(earliestTime, qr.urgentTime);
            }
        }
        synchronized(ackRequestQueue) {
            if(!ackRequestQueue.isEmpty()) {
                QueuedAckRequest qr = (QueuedAckRequest) ackRequestQueue.getLowest();
                earliestTime = Math.min(earliestTime, qr.urgentTime);
            }
        }
        return earliestTime;
    }

    /**
     * @return The last sent new packet number.
     */
    public int getLastOutgoingSeqNumber() {
        synchronized(this) {
            return nextPacketNumber-1;
        }
    }

    /**
     * Report a packet has been sent
     * @param data The data we have just sent (payload only, decrypted). 
     * @param seqNumber The packet number.
     */
    public void sentPacket(byte[] data, int seqNumber) {
        sentPacketsContents.add(seqNumber, data);
    }
}
