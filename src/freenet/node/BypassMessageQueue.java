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
    private final Executor executor;
    protected final String targetName;
    private byte[] sourcePubKeyHash;
    PeerNode sourceNode;
    
    private static volatile boolean logMINOR;
    private static volatile boolean logDEBUG;

    static {
        Logger.registerClass(BypassMessageQueue.class);
    }

    public BypassMessageQueue(Node target, Node source, 
            NodeCrypto targetCrypto, NodeCrypto sourceCrypto) {
        targetNode = target;
        // Only used for logging.
        targetHandler = targetCrypto.socket;
        executor = target.executor;
        targetName = "node on port " + targetCrypto.portNumber;
        sourcePubKeyHash = sourceCrypto.pubKeyHash;
    }

    @Override
    public int queueAndEstimateSize(final MessageItem item, int maxSize) {
        if(logDEBUG)
            Logger.debug(this, "Sending message "+item.msg+" to "+targetName);
        executor.execute(new PrioRunnable() {

            @Override
            public void run() {
                AsyncMessageCallback[] callbacks = item.cb;
                if(callbacks != null) {
                    for(AsyncMessageCallback item : callbacks) {
                        item.sent();
                    }
                }
                PeerNode pn = getSourceNode();
                Message msg = new Message(item.msg, pn);
                pn.receivedPacket(true, true);
                pn.handleMessage(msg);
                if(callbacks != null) {
                    for(AsyncMessageCallback item : callbacks) {
                        item.acknowledged();
                    }
                    sourceNode.receivedAck(System.currentTimeMillis());
                }
            }

            @Override
            public int getPriority() {
                return NativeThread.NORM_PRIORITY;
            }
            
        });
        return 0;
    }

    protected synchronized PeerNode getSourceNode() {
        if(sourceNode != null) return sourceNode;
        sourceNode = targetNode.peers.getByPubKeyHash(sourcePubKeyHash);
        return sourceNode;
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
