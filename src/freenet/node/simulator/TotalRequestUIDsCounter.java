package freenet.node.simulator;

import freenet.node.Node;
import freenet.node.RequestTrackerSnooper;
import freenet.node.UIDTag;

/** Tracks the total number of UIDTag's across one or more nodes and allows waiting
 * until there is no traffic. A version of this using AtomicLong and Semaphore might
 * be cheaper but would be fragile. */
public class TotalRequestUIDsCounter implements RequestTrackerSnooper {

    private long runningUIDs;
    
    @Override
    public synchronized void onLock(UIDTag tag, Node node) {
        runningUIDs++;
    }

    @Override
    public synchronized void onUnlock(UIDTag tag, Node node) {
        runningUIDs--;
        assert(runningUIDs >= 0);
        if(runningUIDs == 0) notifyAll();
    }

    /** Waits for the total number of running requests to reach 0.
     * There is no guarantee that it is still 0 when we return, simply that
     * at some point it has been 0. 
     * @throws InterruptedException */
    public synchronized void waitForNoRequests() throws InterruptedException {
        while(runningUIDs > 0)
            wait();
    }
    
    /** Gets the current value of the total number of UIDTag's registered.
     * This will be out of date on returning. */
    public synchronized long getCount() {
        return runningUIDs;
    }
}
