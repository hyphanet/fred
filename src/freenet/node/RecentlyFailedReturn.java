package freenet.node;

/** Contains information on why we can't route a request. Initially just a flag
 * and a time. */
public class RecentlyFailedReturn {
	
	private boolean recentlyFailed;
	private long wakeup;
	private int countWaiting;
	public synchronized void fail(int countWaiting, long wakeupTime) {
		this.countWaiting = countWaiting;
		this.wakeup = wakeupTime;
		this.recentlyFailed = true;
	}
	public synchronized long recentlyFailed() {
		if(recentlyFailed)
			return wakeup;
		else
			return -1;
	}

}
