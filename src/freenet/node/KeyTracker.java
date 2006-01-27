package freenet.node;

import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;

import freenet.crypt.BlockCipher;
import freenet.io.comm.NotConnectedException;
import freenet.io.xfer.PacketThrottle;
import freenet.support.DoublyLinkedList;
import freenet.support.DoublyLinkedListImpl;
import freenet.support.IndexableUpdatableSortedLinkedListItem;
import freenet.support.LimitedRangeIntByteArrayMap;
import freenet.support.LimitedRangeIntByteArrayMapElement;
import freenet.support.Logger;
import freenet.support.UpdatableSortedLinkedListItem;
import freenet.support.UpdatableSortedLinkedListKilledException;
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
    
    /** Key for above cipher, so far for debugging */
    public final byte[] sessionKey;
    
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
     * because they asked for them. Just contains the numbers. */
    private final HashSet packetsToResend;
    
    /** Ranges of packet numbers we have received from the other
     * side. */
    private final ReceivedPacketNumbers packetNumbersReceived;
    
    /** Counter to generate the next packet number */
    private int nextPacketNumber;
    
    /** Everything is clear to start with */
    KeyTracker(PeerNode pn, BlockCipher cipher, byte[] sessionKey) {
        this.pn = pn;
        this.sessionCipher = cipher;
        this.sessionKey = sessionKey;
        ackQueue = new DoublyLinkedListImpl();
        highestSeenIncomingSerialNumber = -1;
        // give some leeway
        sentPacketsContents = new LimitedRangeIntByteArrayMap(128);
        resendRequestQueue = new UpdatableSortedLinkedListWithForeignIndex();
        ackRequestQueue = new UpdatableSortedLinkedListWithForeignIndex();
        packetsToResend = new HashSet();
        packetNumbersReceived = new ReceivedPacketNumbers(512);
        isDeprecated = false;
        nextPacketNumber = pn.node.random.nextInt(100*1000);
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
            return super.toString()+": packet "+packetNumber+" urgent@"+urgentTime+"("+(System.currentTimeMillis()-urgentTime)+")";
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

        private DoublyLinkedList parent;
        
		public DoublyLinkedList getParent() {
			return parent;
		}

		public DoublyLinkedList setParent(DoublyLinkedList l) {
			DoublyLinkedList old = parent;
			parent = l;
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
        
        void sent() throws UpdatableSortedLinkedListKilledException {
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
            if(packetNumber > r.packetNumber) return 1;
            if(packetNumber < r.packetNumber) return -1;
            return 0;
        }
        
        public Object indexValue() {
            return packetNumberAsInteger;
        }
        
        private DoublyLinkedList parent;
        
		public DoublyLinkedList getParent() {
			return parent;
		}

		public DoublyLinkedList setParent(DoublyLinkedList l) {
			DoublyLinkedList old = parent;
			parent = l;
			return old;
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
        
        void sent() throws UpdatableSortedLinkedListKilledException {
            synchronized(resendRequestQueue) {
                super.sent();
                resendRequestQueue.update(this);
            }
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
        
        void sent() throws UpdatableSortedLinkedListKilledException {
            synchronized(ackRequestQueue) {
                super.sent();
                ackRequestQueue.update(this);
            }
        }
    }
    
    /**
     * Called when we receive a packet.
     * @param seqNumber The packet's serial number.
     * See the comments in FNPPacketMangler.processOutgoing* for
     * the reason for the locking.
     */
    public synchronized void receivedPacket(int seqNumber) {
    	Logger.minor(this, "Received packet "+seqNumber);
        try {
			pn.receivedPacket();
		} catch (NotConnectedException e) {
			Logger.minor(this, "Ignoring, because disconnected");
			return;
		}
        if(seqNumber == -1) return;
        // FIXME delete this log statement
        Logger.minor(this, "Still received packet: "+seqNumber);
        // Received packet
        receivedPacketNumber(seqNumber);
        // Ack it even if it is a resend
        queueAck(seqNumber);
    }

    protected void receivedPacketNumber(int seqNumber) {
    	Logger.minor(this, "Handling received packet number "+seqNumber);
        queueResendRequests(seqNumber);
        packetNumbersReceived.got(seqNumber);
        try {
			removeResendRequest(seqNumber);
		} catch (UpdatableSortedLinkedListKilledException e) {
			// Ignore, not our problem
		}
        synchronized(this) {
        	highestSeenIncomingSerialNumber = Math.max(highestSeenIncomingSerialNumber, seqNumber);
        }
        Logger.minor(this, "Handled received packet number "+seqNumber);
    }
    
    /**
     * Remove a resend request from the queue.
     * @param seqNumber
     * @throws UpdatableSortedLinkedListKilledException 
     */
    private void removeResendRequest(int seqNumber) throws UpdatableSortedLinkedListKilledException {
    	resendRequestQueue.removeByKey(new Integer(seqNumber));
    }

    /**
     * Add some resend requests if necessary.
     * @param seqNumber The number of the packet we just received.
     */
    private void queueResendRequests(int seqNumber) {
        int max;
        synchronized(this) {
        	max = packetNumbersReceived.highest();
        }
        if(seqNumber > max) {
        	try {
            if(max != -1 && seqNumber - max > 1) {
                Logger.minor(this, "Queueing resends from "+max+" to "+seqNumber);
                // Missed some packets out
                for(int i=max+1;i<seqNumber;i++) {
                    queueResendRequest(i);
                }
            }
        	} catch (UpdatableSortedLinkedListKilledException e) {
        		// Ignore (we are decoding packet, not sending one)
        	}
        }
    }

    /**
     * Queue a resend request
     * @param packetNumber the packet serial number to queue a
     * resend request for
     * @throws UpdatableSortedLinkedListKilledException 
     */
    private void queueResendRequest(int packetNumber) throws UpdatableSortedLinkedListKilledException {
    	synchronized(resendRequestQueue) {
    		if(queuedResendRequest(packetNumber)) {
    			Logger.minor(this, "Not queueing resend request for "+packetNumber+" - already queued");
    			return;
    		}
    		Logger.minor(this, "Queueing resend request for "+packetNumber);
    		QueuedResendRequest qrr = new QueuedResendRequest(packetNumber);
    		resendRequestQueue.add(qrr);
    	}
    }

    /**
     * Queue an ack request
     * @param packetNumber the packet serial number to queue a
     * resend request for
     * @throws UpdatableSortedLinkedListKilledException 
     */
    private void queueAckRequest(int packetNumber) throws UpdatableSortedLinkedListKilledException {
        synchronized(ackRequestQueue) {
            if(queuedAckRequest(packetNumber)) {
                Logger.minor(this, "Not queueing ack request for "+packetNumber+" - already queued");
                return;
            }
            Logger.minor(this, "Queueing ack request for "+packetNumber+" on "+this);
            QueuedAckRequest qrr = new QueuedAckRequest(packetNumber, false);
            ackRequestQueue.add(qrr);
        }
    }

    /**
     * Is an ack request queued for this packet number?
     */
    private boolean queuedAckRequest(int packetNumber) {
        return ackRequestQueue.containsKey(new Integer(packetNumber));
    }

    /**
     * Is a resend request queued for this packet number?
     */
    private boolean queuedResendRequest(int packetNumber) {
        return resendRequestQueue.containsKey(new Integer(packetNumber));
    }

    /**
     * Called when we have received several packet acknowledgements.
     * Synchronized for the same reason as the sender code is:
     * So that we don't end up sending packets too late when overloaded,
     * and get horrible problems such as asking to resend packets which
     * haven't been sent yet.
     */
    public synchronized void acknowledgedPackets(int[] seqNos) {
    	AsyncMessageCallback[][] callbacks = new AsyncMessageCallback[seqNos.length][];
   		for(int i=0;i<seqNos.length;i++) {
   			int realSeqNo = seqNos[i];
           	Logger.minor(this, "Acknowledged packet: "+realSeqNo);
            try {
				removeAckRequest(realSeqNo);
			} catch (UpdatableSortedLinkedListKilledException e) {
				// Ignore, we are processing an incoming packet
			}
            Logger.minor(this, "Removed ack request");
            callbacks[i] = sentPacketsContents.getCallbacks(realSeqNo);
            byte[] buf = sentPacketsContents.get(realSeqNo);
            long timeAdded = sentPacketsContents.getTime(realSeqNo);
            if(sentPacketsContents.remove(realSeqNo)) {
            	if(buf.length > Node.PACKET_SIZE) {
            		PacketThrottle throttle = getThrottle();
            		throttle.notifyOfPacketAcknowledged();
            		throttle.setRoundTripTime(System.currentTimeMillis() - timeAdded);
            	}
            }
  		}
    	int cbCount = 0;
    	for(int i=0;i<callbacks.length;i++) {
    		AsyncMessageCallback[] cbs = callbacks[i];
    		if(cbs != null) {
    			for(int j=0;j<cbs.length;j++) {
    				cbs[j].acknowledged();
    				cbCount++;
    			}
    		}
    	}
    	if(cbCount > 0)
    		Logger.minor(this, "Executed "+cbCount+" callbacks");
    }
    
    private PacketThrottle getThrottle() {
    	return PacketThrottle.getThrottle(pn.getPeer(), Node.PACKET_SIZE);
	}

	/**
     * Called when we have received a packet acknowledgement.
     * @param realSeqNo
     */
    public void acknowledgedPacket(int realSeqNo) {
        AsyncMessageCallback[] callbacks;
       	Logger.minor(this, "Acknowledged packet: "+realSeqNo);
        try {
			removeAckRequest(realSeqNo);
		} catch (UpdatableSortedLinkedListKilledException e) {
			// Ignore, we are processing an incoming packet
		}
        Logger.minor(this, "Removed ack request");
        callbacks = sentPacketsContents.getCallbacks(realSeqNo);
        byte[] buf = sentPacketsContents.get(realSeqNo);
        long timeAdded = sentPacketsContents.getTime(realSeqNo);
        if(sentPacketsContents.remove(realSeqNo)) {
        	if(buf.length > Node.PACKET_SIZE) {
        		PacketThrottle throttle = getThrottle();
        		throttle.notifyOfPacketAcknowledged();
        		throttle.setRoundTripTime(System.currentTimeMillis() - timeAdded);
        	}
        }
        if(callbacks != null) {
            for(int i=0;i<callbacks.length;i++)
                callbacks[i].acknowledged();
            Logger.minor(this, "Executed "+callbacks.length+" callbacks");
        }
    }

    /**
     * Remove an ack request from the queue by packet number.
     * @throws UpdatableSortedLinkedListKilledException 
     */
    private void removeAckRequest(int seqNo) throws UpdatableSortedLinkedListKilledException {
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
        	if(resendData.length > Node.PACKET_SIZE)
        		getThrottle().notifyOfPacketLost();
            synchronized(packetsToResend) {
                packetsToResend.add(new Integer(seqNumber));
            }
            pn.node.ps.queuedResendPacket();
        } else {
        	synchronized(this) {
        		String msg = "Asking me to resend packet "+seqNumber+
        			" which we haven't sent yet or which they have already acked (next="+nextPacketNumber+")";
        		// Probably just a bit late - caused by overload etc
        		Logger.minor(this, msg);
        	}
        }
    }

    /**
     * Called when we receive an AckRequest.
     * @param packetNumber The packet that the other side wants
     * us to re-ack.
     */
    public synchronized void receivedAckRequest(int packetNumber) {
        if(queuedAck(packetNumber)) {
            // Already going to send an ack
            // Don't speed it up though; wasteful
        } else {
            if(packetNumbersReceived.contains(packetNumber)) {
                // We have received it, so send them an ack
                queueAck(packetNumber);
            } else {
                // We have not received it, so get them to resend it
                try {
					queueResendRequest(packetNumber);
				} catch (UpdatableSortedLinkedListKilledException e) {
					// Ignore, we are decoding, not sending.
				}
                highestSeenIncomingSerialNumber = Math.max(highestSeenIncomingSerialNumber, packetNumber);
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
    public synchronized void destForgotPacket(int seqNumber) {
        if(isDeprecated) {
            Logger.normal(this, "Destination forgot packet: "+seqNumber);
        } else {
            Logger.error(this, "Destination forgot packet: "+seqNumber);
        }
        try {
			removeResendRequest(seqNumber);
		} catch (UpdatableSortedLinkedListKilledException e) {
			// Ignore
		}
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
            if(isDeprecated) throw new KeyChangedException();
            packetNumber = nextPacketNumber++;
            Logger.minor(this, "Allocated "+packetNumber+" in allocateOutgoingPacketNumber for "+this);
        }
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
            packetNumber = nextPacketNumber;
            if(isDeprecated) throw new KeyChangedException();
            sentPacketsContents.lockNeverBlock(packetNumber);
            nextPacketNumber = packetNumber+1;
            Logger.minor(this, "Allocated "+packetNumber+" in allocateOutgoingPacketNumberNeverBlock for "+this);
            return packetNumber;
        }
    }

    /**
     * Grab all the currently queued acks to be sent to this node.
     * @return An array of packet numbers that we need to acknowledge.
     */
    public int[] grabAcks() {
    	Logger.minor(this, "Grabbing acks");
        int[] acks;
        synchronized(ackQueue) {
            // Grab the acks and tell them they are sent
            int length = ackQueue.size();
            acks = new int[length];
            int i=0;
            for(Enumeration e=ackQueue.elements();e.hasMoreElements();) {
                QueuedAck ack = (QueuedAck)e.nextElement();
                acks[i++] = ack.packetNumber;
                Logger.minor(this, "Grabbing ack "+ack.packetNumber+" from "+this);
                ack.sent();
            }
        }
        return acks;
    }

    /**
     * Grab all the currently queued resend requests.
     * @return An array of the packet numbers of all the packets we want to be resent.
     * @throws NotConnectedException If the peer is no longer connected.
     */
    public int[] grabResendRequests() throws NotConnectedException {
        UpdatableSortedLinkedListItem[] items;
        int[] packetNumbers;
        int realLength;
        long now = System.currentTimeMillis();
        try {
        synchronized(resendRequestQueue) {
            items = resendRequestQueue.toArray();
            int length = items.length;
            packetNumbers = new int[length];
            realLength = 0;
            for(int i=0;i<length;i++) {
                QueuedResendRequest qrr = (QueuedResendRequest)items[i];
                if(packetNumbersReceived.contains(qrr.packetNumber)) {
                	Logger.minor(this, "Have already seen "+qrr.packetNumber+", removing from resend list");
                	resendRequestQueue.remove(qrr);
                	continue;
                }
                if(qrr.activeTime <= now) {
                    packetNumbers[realLength++] = qrr.packetNumber;
                    Logger.minor(this, "Grabbing resend request: "+qrr.packetNumber+" from "+this);
                    qrr.sent();
                } else {
                    Logger.minor(this, "Rejecting resend request: "+qrr.packetNumber+" - in future by "+(qrr.activeTime-now)+"ms for "+this);
                }
            }
        }
        } catch (UpdatableSortedLinkedListKilledException e) {
        	throw new NotConnectedException();
        }
        int[] trimmedPacketNumbers = new int[realLength];
        System.arraycopy(packetNumbers, 0, trimmedPacketNumbers, 0, realLength);
        return trimmedPacketNumbers;
    }

    public int[] grabAckRequests() throws NotConnectedException {
        UpdatableSortedLinkedListItem[] items;
        int[] packetNumbers;
        int realLength;
        Logger.minor(this, "Grabbing ack requests");
        try {
        synchronized(ackRequestQueue) {
            long now = System.currentTimeMillis();
            items = ackRequestQueue.toArray();
            int length = items.length;
            packetNumbers = new int[length];
            realLength = 0;
            for(int i=0;i<length;i++) {
                QueuedAckRequest qr = (QueuedAckRequest)items[i];
                int packetNumber = qr.packetNumber;
                if(qr.activeTime <= now) {
                    if(sentPacketsContents.get(packetNumber) == null) {
                        Logger.minor(this, "Asking to ack packet which has already been acked: "+packetNumber+" on "+this+".grabAckRequests");
                        ackRequestQueue.remove(qr);
                        continue;
                    }
                    packetNumbers[realLength++] = packetNumber;
                    Logger.minor(this, "Grabbing ack request "+packetNumber+" from "+this);
                    qr.sent();
                } else {
                    Logger.minor(this, "Ignoring ack request "+packetNumber+" - will become active in "+(qr.activeTime-now)+" ms on "+this+" - "+qr);
                }
            }
        }
        } catch (UpdatableSortedLinkedListKilledException e) {
        	throw new NotConnectedException();
        }
        int[] trimmedPacketNumbers = new int[realLength];
        System.arraycopy(packetNumbers, 0, trimmedPacketNumbers, 0, realLength);
        Logger.minor(this, "Returning "+trimmedPacketNumbers.length+" ackRequests");
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
     * @throws NotConnectedException 
     */
    public void sentPacket(byte[] data, int seqNumber, AsyncMessageCallback[] callbacks) throws NotConnectedException {
        if(callbacks != null) {
            for(int i=0;i<callbacks.length;i++) {
                if(callbacks[i] == null)
                    throw new NullPointerException();
            }
        }
        sentPacketsContents.add(seqNumber, data, callbacks);
        try {
			queueAckRequest(seqNumber);
		} catch (UpdatableSortedLinkedListKilledException e) {
			throw new NotConnectedException();
		}
    }

    public void completelyDeprecated(KeyTracker newTracker) {
        Logger.minor(this, "Completely deprecated: "+this+" in favour of "+newTracker);
        isDeprecated = true;
        LimitedRangeIntByteArrayMapElement[] elements;
        synchronized(sentPacketsContents) {
            // Anything to resend?
            elements = sentPacketsContents.grabAll();
        }
        MessageItem[] messages = new MessageItem[elements.length];
        for(int i=0;i<elements.length;i++) {
            LimitedRangeIntByteArrayMapElement element = elements[i];
            byte[] buf = element.data;
            AsyncMessageCallback[] callbacks = element.callbacks;
            // Ignore packet#
            Logger.minor(this, "Queueing resend of what was once "+element.packetNumber);
            messages[i] = new MessageItem(buf, callbacks, true);
        }
        pn.requeueMessageItems(messages, 0, messages.length, true);
        pn.node.ps.queuedResendPacket();
    }

    /**
     * Called when the node appears to have been disconnected.
     * Dump all sent messages.
     */
    public void disconnected() {
        isDeprecated = true;
        LimitedRangeIntByteArrayMapElement[] elements;
        // Clear everything, call the callbacks
        synchronized(sentPacketsContents) {
            // Anything to resend?
            elements = sentPacketsContents.grabAll();
        }
        for(int i=0;i<elements.length;i++) {
            LimitedRangeIntByteArrayMapElement element = elements[i];
            AsyncMessageCallback[] callbacks = element.callbacks;
            if(callbacks != null) {
                for(int j=0;j<callbacks.length;j++)
                    callbacks[j].disconnected();
            }
        }
        synchronized(ackQueue) {
            ackQueue.clear();
        }
        resendRequestQueue.kill();
        ackRequestQueue.kill();
        synchronized(packetsToResend) {
            packetsToResend.clear();
        }
    }

    /**
     * @return An array of packets that need to be resent, if any.
     * Some of the elements may be null. Otherwise null.
     */
    public ResendPacketItem[] grabResendPackets() {
        int[] numbers;
        synchronized(packetsToResend) {
            int len = packetsToResend.size();
            numbers = new int[len];
            int i=0;
            for(Iterator it=packetsToResend.iterator();it.hasNext();) {
                int packetNo = ((Integer)it.next()).intValue();
                numbers[i++] = packetNo;
            }
            packetsToResend.clear();
        }
        ResendPacketItem[] items = new ResendPacketItem[numbers.length];
        for(int i=0;i<numbers.length;i++) {
            int packetNo = numbers[i];
            byte[] buf = sentPacketsContents.get(packetNo);
            if(buf == null) {
                Logger.minor(this, "Contents null for "+packetNo+" in grabResendPackets on "+this);
                continue; // acked already?
            }
            AsyncMessageCallback[] callbacks = sentPacketsContents.getCallbacks(packetNo);
            items[i] = new ResendPacketItem(buf, packetNo, this, callbacks);
        }
        return items;
    }
}
