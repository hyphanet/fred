package freenet.node;

/** Contains information on why we can't route a request. Initially just a flag
 * and a time. */
public class RecentlyFailedReturn {
	
	private boolean recentlyFailed;
	private int delta;
	private int countWaiting;
	public synchronized void fail(int countWaiting, int delta) {
		this.countWaiting = countWaiting;
		this.delta = delta;
		this.recentlyFailed = true;
	}
	public synchronized int recentlyFailed() {
		if(recentlyFailed)
			return delta;
		else
			return -1;
	}

}
