package freenet.node;

import freenet.support.Ticker;

public class SnoopingRequestTracker extends RequestTracker {

    final Node node;
    final RequestTrackerSnooper callback;
    
    public SnoopingRequestTracker(PeerManager peers, Ticker ticker, Node node,
            RequestTrackerSnooper cb) {
        super(peers, ticker);
        this.node = node;
        this.callback = cb;
    }
    
    @Override
    public boolean lockUID(long uid, boolean ssk, boolean insert, boolean offerReply, boolean local, boolean realTimeFlag, UIDTag tag) {
        boolean ret = super.lockUID(uid, ssk, insert, offerReply, local, realTimeFlag, tag);
        callback.onLock(tag, node);
        return ret;
    }

    @Override
    void unlockUID(UIDTag tag, boolean canFail, boolean noRecord) {
        super.unlockUID(tag, canFail, noRecord);
        callback.onUnlock(tag, node);
    }
}
