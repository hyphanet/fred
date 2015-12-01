package freenet.node;

import freenet.io.comm.AsyncMessageCallback;
import freenet.io.comm.Message;
import freenet.support.Logger;
import freenet.support.Ticker;
import freenet.support.io.NativeThread;

/** Message queue bypass simulating a constant bit-rate link. */
public class SlowBypassMessageQueue extends BypassMessageQueue {

    private final Ticker ticker;
    
    private static volatile boolean logMINOR;
    private static volatile boolean logDEBUG;

    /** Per link bandwidth limit converted to nanoseconds per byte */
    private final long nanosecondsPerByte;
    
    /** Per-link fixed latency */
    private final int latencyMS;
    
    /** Time in nanoseconds at which we treat the next packet as arriving
     * (based on bandwidth only, not latency). */
    private long nextDeliveryTimeNS;
    
    static final long NANOS_PER_MILLISECOND = 1000*1000;

    static {
        Logger.registerClass(BypassMessageQueue.class);
    }

    /** Create a message queue bypass which simulates a constant bit-rate link.
     * @param target The destination node.
     * @param source The originating node.
     * @param targetCrypto The destination port details (e.g. darknet).
     * @param sourceCrypto The originating port details.
     * @param bytesPerSecond Bandwidth of this link, independent of other links.
     * @param latencyMS One-way delivery time in milliseconds, added to the delay
     * caused by the simulated data transfer.
     */
    public SlowBypassMessageQueue(Node target, Node source, NodeCrypto targetCrypto,
            NodeCrypto sourceCrypto, int bytesPerSecond, int latencyMS) {
        super(target, source, targetCrypto, sourceCrypto);
        ticker = target.getTicker();
        nanosecondsPerByte = (1000*1000*1000) / bytesPerSecond;
        if(nanosecondsPerByte < 1) throw new IllegalArgumentException();
        nextDeliveryTimeNS = System.currentTimeMillis() * NANOS_PER_MILLISECOND;
        this.latencyMS = latencyMS;
    }
    
    @Override
    public int queueAndEstimateSize(final MessageItem item, int maxSize) {
        if(logDEBUG)
            Logger.debug(this, "Sending message "+item.msg+" to "+targetName);
        int messageSize = item.getLength();
        long deliveryTime;
        synchronized(this) {
            long nowMS = System.currentTimeMillis();
            long nowNS = nowMS * NANOS_PER_MILLISECOND;
            if(nextDeliveryTimeNS < nowNS) 
                nextDeliveryTimeNS = nowNS;
            nextDeliveryTimeNS += (messageSize * nanosecondsPerByte);
            deliveryTime = (nextDeliveryTimeNS / NANOS_PER_MILLISECOND) - nowMS;
        }
        ticker.queueTimedJob(new PrioRunnable() {
            @Override
            public void run() {
                final AsyncMessageCallback[] callbacks = item.cb;
                if(callbacks != null) {
                    for(AsyncMessageCallback item : callbacks) {
                        item.sent();
                    }
                }
                Message msg = new Message(item.msg, getSourceNode());
                targetMessageCore.checkFilters(msg, targetHandler);
                ticker.queueTimedJob(new PrioRunnable() {

                    @Override
                    public void run() {
                        if(callbacks != null) {
                            for(AsyncMessageCallback item : callbacks) {
                                item.acknowledged();
                            }
                        }
                    }

                    @Override
                    public int getPriority() {
                        return NativeThread.HIGH_PRIORITY;
                    }
                    
                }, latencyMS);
            }

            @Override
            public int getPriority() {
                return NativeThread.NORM_PRIORITY;
            }
            
            
        }, deliveryTime + latencyMS);
        return 0;
    }



}
