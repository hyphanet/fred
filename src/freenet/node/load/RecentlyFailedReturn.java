package freenet.node.load;

import freenet.support.Logger;
import freenet.support.TimeUtil;

/** Contains information on why we can't route a request. Initially just a flag
 * and a time. */
public class RecentlyFailedReturn {
	
    private static volatile boolean logMINOR;
    static {
        Logger.registerClass(RecentlyFailedReturn.class);
    }
	
	private boolean recentlyFailed;
	private long wakeup;
	private int countWaiting;
	public synchronized void fail(int countWaiting, long wakeupTime) {
 		if(logMINOR) Logger.minor(this, "RecentlyFailed until "+TimeUtil.formatTime(wakeupTime - System.currentTimeMillis()));
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
