package freenet.node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import freenet.io.comm.Peer;
import freenet.io.comm.UdpSocketHandler;
import freenet.support.Executor;
import freenet.support.Logger;

/** Forwards MessageItem's directly between nodes within a single JVM. Used for simulations where
 * we need a bit more accuracy than BypassMessageQueue: Since PacketSender is still used, bandwidth 
 * is shared between a node's peers, rather than having to emulate constant bitrate connections, 
 * and a real queue is used, so message priorities etc are also modelled accurately. See 
 * NodeStarter.TestingVMBypass. Does not simulate low-level network congestion e.g. no packets are
 * lost or reordered.
 * @author toad
 */
public class BypassPacketFormat extends BypassBase implements PacketFormat {
    
    private static volatile boolean logMINOR;
    private static volatile boolean logDEBUG;
    static {
        Logger.registerClass(BypassPacketFormat.class);
    }
    
    private final Executor sourceExecutor;
    private final Executor targetExecutor;
    private final MessageQueue messageQueue;
    /** Number of bytes sent for each MessageItem in flight. Once it has been sent in its entirety,
     * we call the sent callback, and remove it from the map. */
    private final Map<MessageItem,Integer> messagesInFlight;
    /** Messages which have been entirely received and must be acked on the next returning packet */
    private final List<MessageItem> messagesToAck;
    /** Time at which the oldest MessageItem in messagesToAck was received. */
    private long oldestMessageToAckReceivedTime;
    static final int MAX_ACK_DELAY = 200;
    static final int MAX_ACKS = NewPacketFormat.MAX_ACKS;
    static final int PACKET_OVERHEAD_SIZE = NewPacketFormat.HMAC_LENGTH + 4 /* Packet number */ + 
        32 /* IV */ + 1 /* number of acks, not limited here */;
    static final int FRAGMENT_HEADER_LENGTH = 9;
    
    /** A virtual packet. */
    private class BypassPacket {
        public BypassPacket(MessageItem[] toDeliver, MessageItem[] toAck) {
            this.toDeliver = toDeliver;
            this.toAck = toAck;
        }
        /** MessageItem's that have now been completely sent and should be delivered on receipt of
         * this packet. */
        final MessageItem[] toDeliver;
        /** MessageItem's that have been completely received by the other side and should be 
         * acknowledged. */
        final MessageItem[] toAck;
    }

    BypassPacketFormat(MessageQueue queue, Node sourceNode, Node targetNode, 
            NodeCrypto sourceCrypto, NodeCrypto targetCrypto) {
        super(sourceNode, targetNode, sourceCrypto, targetCrypto);
        this.messageQueue = queue;
        this.sourceExecutor = sourceNode.executor;
        this.targetExecutor = targetNode.executor;
        messagesInFlight = new HashMap<MessageItem, Integer>();
        messagesToAck = new ArrayList<MessageItem>();
        oldestMessageToAckReceivedTime = Long.MAX_VALUE;
    }
    
    @Override
    public boolean handleReceivedPacket(byte[] buf, int offset, int length, long now, Peer replyTo) {
        assert(false);
        return false;
    }

    /** Construct a virtual packet, queue it on the Ticker to be delivered to the other side, and 
     * consume the required number of bytes from the bandwidth limiter. */
    @Override
    public boolean maybeSendPacket(long now, boolean ackOnly) throws BlockedTooLongException {
        MessageItem[] toAck;
        MessageItem[] toDeliverMessages;
        int length = PACKET_OVERHEAD_SIZE;
        boolean payload = false;
        PeerNode sourcePeerNode = getTargetPeerNodeAtSource();
        int maxPacketSize = sourcePeerNode.getMaxPacketSize();
        synchronized(this) {
            boolean mustSend = false;
            if(!messagesToAck.isEmpty()) {
                if(now - oldestMessageToAckReceivedTime > MAX_ACK_DELAY) mustSend = true;
                else if(messagesToAck.size() > MAX_ACKS) mustSend = true;
                length += messagesToAck.size(); // 1 byte per ack
            }
            if(!mustSend && !ackOnly) {
                if(!messagesInFlight.isEmpty())
                    mustSend = true;
                else if(messageQueue.mustSendNow(now) || 
                        messageQueue.mustSendSize(length, maxPacketSize))
                    mustSend = true;
            }
            
            if(!mustSend) return false;
            // No keepalive packets.
            // FIXME No lossy messages.
            List<MessageItem> toDeliver = null;
            if(!ackOnly) {
                // Complete existing message sends.
                while(length + FRAGMENT_HEADER_LENGTH + 1 < maxPacketSize && 
                        !messagesInFlight.isEmpty()) {
                    for(Map.Entry<MessageItem, Integer> entry : messagesInFlight.entrySet()) {
                        MessageItem item = entry.getKey();
                        int itemLength = item.getLength();
                        int sent = entry.getValue();
                        assert(sent < itemLength);
                        int sendBytes = Math.min(itemLength - sent, 
                                maxPacketSize - length - FRAGMENT_HEADER_LENGTH);
                        length += sendBytes + FRAGMENT_HEADER_LENGTH;
                        payload = true;
                        if(sendBytes + sent == itemLength) {
                            messagesInFlight.remove(item);
                            if(toDeliver == null) toDeliver = new ArrayList<MessageItem>();
                            toDeliver.add(item);
                        } else {
                            entry.setValue(sent + sendBytes);
                            break; // Filed up packet.
                        }
                    }
                }
                
                // Send new messages.
                while(length + FRAGMENT_HEADER_LENGTH + 1 < maxPacketSize) {
                    MessageItem item = messageQueue.grabQueuedMessageItem(0);
                    if(item == null) break;
                    int itemLength = item.getLength();
                    int sendBytes = Math.min(itemLength, 
                            maxPacketSize - length - FRAGMENT_HEADER_LENGTH);
                    payload = true;
                    if(sendBytes == itemLength) {
                        if(toDeliver == null) toDeliver = new ArrayList<MessageItem>();
                        toDeliver.add(item);
                    } else {
                        messagesInFlight.put(item, sendBytes);
                        break; // Filled up packet.
                    }
                }
            }
            toAck = messagesToAck.toArray(new MessageItem[messagesToAck.size()]);
            messagesToAck.clear();
            oldestMessageToAckReceivedTime = Long.MAX_VALUE;
            toDeliverMessages = toDeliver.toArray(new MessageItem[toDeliver.size()]);
        }
        BypassPacket packet = 
            new BypassPacket(toDeliverMessages, toAck);
        if(logMINOR) Logger.minor(this, "Sending packet with "+toDeliverMessages.length
                +" messages and "+toAck.length+" acks");
        callSentCallbacks(toDeliverMessages);
        // We do not need to deal with IOStatisticsCollector.
        // The PeerNode methods are sufficient.
        sourcePeerNode.sentPacket();
        sourcePeerNode.reportOutgoingBytes(length);
        if(sourcePeerNode.shouldThrottle())
            sourcePeerNode.sentThrottledBytes(length);
        if(!payload)
            sourcePeerNode.onNotificationOnlyPacketSent(length);
        queuePacketDelivery(packet);
        return true;
    }

    private void queuePacketDelivery(final BypassPacket packet) {
        targetExecutor.execute(new Runnable() {

            @Override
            public void run() {
                deliverPacket(packet);
            }
            
        });
    }

    protected void deliverPacket(BypassPacket packet) {
        if(logMINOR) Logger.minor(this, "Delivering packet with "+packet.toDeliver.length
                +" messages and "+packet.toAck.length+" acks");
        PeerNode targetPeerNode = getSourcePeerNodeAtTarget();
        targetPeerNode.receivedPacket(false, packet.toDeliver.length != 0);
        // Deliver messages.
        for(MessageItem item : packet.toDeliver) {
            // Don't need to use decoding group because the messages won't be incompatible/corrupt.
            targetPeerNode.handleMessage(item.msg.cloneAndKeepSubMessages(targetPeerNode));
        }
        // Queue acknowledgements for messages delivered (for another packet).
        synchronized(this) {
            long now = System.currentTimeMillis();
            if(oldestMessageToAckReceivedTime == Long.MAX_VALUE)
                oldestMessageToAckReceivedTime = now;
            for(MessageItem item : packet.toDeliver)
                messagesToAck.add(item);
        }
        // Process acknowledgements in this packet.
        for(MessageItem item : packet.toAck) {
            item.onAck();
        }
    }

    private void callSentCallbacks(final MessageItem[] toDeliverMessages) {
        sourceExecutor.execute(new Runnable() {

            @Override
            public void run() {
                for(MessageItem item : toDeliverMessages) {
                    item.onSentAll();
                }
            }
            
        });
    }

    @Override
    public synchronized List<MessageItem> onDisconnect() {
        // FIXME not really supported/tested?
        Logger.error(this, "Disconnecting "+this);
        if(messagesInFlight.isEmpty()) return null;
        ArrayList<MessageItem> list = 
            new ArrayList<MessageItem>(messagesInFlight.keySet());
        messagesInFlight.clear();
        return list;
    }

    @Override
    public boolean canSend(SessionKey key) {
        return true; // No packet window exhaustion issues here.
    }

    @Override
    public synchronized long timeNextUrgent(boolean canSend, long now) {
        long urgent = Long.MAX_VALUE;
        for(MessageItem item : messagesInFlight.keySet()) {
            long d = item.getDeadline();
            if(d < urgent) urgent = d;
        }
        if(oldestMessageToAckReceivedTime != Long.MAX_VALUE) {
            urgent = Math.min(urgent, oldestMessageToAckReceivedTime+MAX_ACK_DELAY);
        }
        return urgent;
    }

    @Override
    public synchronized long timeSendAcks() {
        if(oldestMessageToAckReceivedTime != Long.MAX_VALUE) {
            return oldestMessageToAckReceivedTime+MAX_ACK_DELAY;
        } else return Long.MAX_VALUE;
    }

    @Override
    public boolean fullPacketQueued(int maxPacketSize) {
        return messageQueue.
            mustSendSize(PACKET_OVERHEAD_SIZE /* FIXME estimate headers */, maxPacketSize);
    }

    @Override
    public void checkForLostPackets() {
        // Do nothing.
    }

    @Override
    public long timeCheckForLostPackets() {
        return Long.MAX_VALUE;
    }

}
