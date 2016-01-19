package freenet.node;

/** Callback for RequestTracker for testing. LOCKING: May be called on thread in the 
 * middle of other locks, so be careful! */
public interface RequestTrackerSnooper {
    
    public void onLock(UIDTag tag, Node node);
    
    public void onUnlock(UIDTag tag, Node node);

}
