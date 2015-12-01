package freenet.node;

import freenet.io.comm.AsyncMessageCallback;
import freenet.io.comm.Message;
import freenet.io.comm.MessageCore;
import freenet.io.comm.PacketSocketHandler;
import freenet.io.comm.PeerContext;
import freenet.support.Executor;
import freenet.support.Logger;
import freenet.support.io.NativeThread;

/** MessageQueue which bypasses the transport layer and sends messages directly to a Node in
 * the same VM. Used for simulations, but might also be useful if we ever supported multiple
 * Node's with a single UI / client layer.
 * @author toad
 */
public class BypassMessageQueue implements MessageQueue {
    
    final PacketSocketHandler targetHandler;
    private final Node targetNode;
    private final Node sourceNode;
    private final Executor executor;
    protected final String targetName;
    private byte[] sourcePubKeyHash;
    private byte[] targetPubKeyHash;
    PeerNode sourcePeerNodeAtTarget;
    PeerNode targetPeerNodeAtSource;
    
    private static volatile boolean logMINOR;
    private static volatile boolean logDEBUG;

    static {
        Logger.registerClass(BypassMessageQueue.class);
    }

    public BypassMessageQueue(Node target, Node source, 
            NodeCrypto targetCrypto, NodeCrypto sourceCrypto) {
        targetNode = target;
        sourceNode = source;
        // Only used for logging.
        targetHandler = targetCrypto.socket;
        executor = target.executor;
        targetName = "node on port " + targetCrypto.portNumber;
        sourcePubKeyHash = sourceCrypto.pubKeyHash;
        targetPubKeyHash = targetCrypto.pubKeyHash;
    }
    
    @Override
    public int queueAndEstimateSize(final MessageItem item, int maxSize) {
        if(logDEBUG)
            Logger.debug(this, "Sending message "+item.msg+" to "+targetName);
        executor.execute(makeDeliveryJob(item));
        return 0;
    }
    
    protected PrioRunnable makeDeliveryJob(final MessageItem item) {
        final PeerNode originator = getTargetPeerNodeAtSource();
        final long bootID = originator.getBootID();
        final AsyncMessageCallback[] callbacks = item.cb;
        callSentCallbacks(callbacks);
        return new PrioRunnable() {
            
            int count = 0;
            
            @Override
            public void run() {
                PeerNode pn = getSourcePeerNodeAtTarget();
                if(tryToDeliverMessage(item, pn, callbacks)) {
                    callAcks(callbacks, originator);
                } else {
                    // The destination thinks the source is disconnected.
                    // Race condition occurring during connection setup.
                    // In practice we would not acknowledge the packet, so it
                    // would get retried.
                    if(bootID != originator.getBootID() 
                            || !originator.isConnected() 
                            || count++ >= 10) {
                        callDisconnectedCallbacks(callbacks);
                    } else {
                        Logger.error(this, "Race condition in bypass message queue: count="+
                                count+" for message "+item+" from "+
                                sourceNode.getDarknetPortNumber()+" to "+
                                targetNode.getDarknetPortNumber());
                        // Hasn't restarted. Try again with exponential backoff.
                        pn.node.getTicker().queueTimedJob(this, 1 << count);
                    }
                }
            }

            @Override
            public int getPriority() {
                return NativeThread.NORM_PRIORITY;
            }
            
        };
    }

    protected boolean tryToDeliverMessage(MessageItem item, PeerNode pn, 
            AsyncMessageCallback[] callbacks) {
        if(pn.isConnected()) {
            Message msg = new Message(item.msg, pn);
            pn.receivedPacket(true, true);
            pn.handleMessage(msg);
            return true;
        } else return false;
    }

    protected void callSentCallbacks(AsyncMessageCallback[] callbacks) {
        if(callbacks != null) {
            for(AsyncMessageCallback it : callbacks) {
                it.sent();
            }
        }
    }

    protected void callAcks(AsyncMessageCallback[] callbacks, PeerNode originator) {
        if(callbacks != null) {
            for(AsyncMessageCallback it : callbacks) {
                it.acknowledged();
            }
            originator.receivedAck(System.currentTimeMillis());
        }
    }
    
    protected void callDisconnectedCallbacks(AsyncMessageCallback[] callbacks) {
        if(callbacks != null) {
            for(AsyncMessageCallback it : callbacks) {
                it.disconnected();
            }
        }
    }

    /** PeerNode on the target node which represents the source node */
    protected synchronized PeerNode getSourcePeerNodeAtTarget() {
        if(sourcePeerNodeAtTarget != null) return sourcePeerNodeAtTarget;
        sourcePeerNodeAtTarget = targetNode.peers.getByPubKeyHash(sourcePubKeyHash);
        return sourcePeerNodeAtTarget;
    }

    /** PeerNode on the source node which represents the target node */
    protected synchronized PeerNode getTargetPeerNodeAtSource() {
        if(targetPeerNodeAtSource != null) return targetPeerNodeAtSource;
        targetPeerNodeAtSource = sourceNode.peers.getByPubKeyHash(targetPubKeyHash);
        return targetPeerNodeAtSource;
    }

    @Override
    public long getMessageQueueLengthBytes() {
        return 0;
    }

    @Override
    public void pushfrontPrioritizedMessageItem(MessageItem addMe) {
        queueAndEstimateSize(addMe, 0);
    }

    @Override
    public MessageItem[] grabQueuedMessageItems() {
        return null;
    }

    @Override
    public long getNextUrgentTime(long t, long returnIfBefore) {
        return Long.MAX_VALUE;
    }

    @Override
    public boolean mustSendNow(long now) {
        return false;
    }

    @Override
    public boolean mustSendSize(int minSize, int maxSize) {
        return false;
    }

    @Override
    public MessageItem grabQueuedMessageItem(int minPriority) {
        return null;
    }

    @Override
    public boolean removeMessage(MessageItem message) {
        return false;
    }

    @Override
    public void removeUIDsFromMessageQueues(Long[] list) {
        // Ignore.
    }

}
