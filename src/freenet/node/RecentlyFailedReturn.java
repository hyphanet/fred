/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.node;

//~--- non-JDK imports --------------------------------------------------------

import freenet.support.Logger;
import freenet.support.TimeUtil;

/**
 * Contains information on why we can't route a request. Initially just a flag
 * and a time. 
 */
public class RecentlyFailedReturn {
    private static volatile boolean logMINOR;

    static {
        Logger.registerClass(RecentlyFailedReturn.class);
    }

    private boolean recentlyFailed;
    private long wakeup;

    public synchronized void fail(int countWaiting, long wakeupTime) {
        if (logMINOR) {
            Logger.minor(this, "RecentlyFailed until " + TimeUtil.formatTime(wakeupTime - System.currentTimeMillis()));
        }

        this.wakeup = wakeupTime;
        this.recentlyFailed = true;
    }

    public synchronized long recentlyFailed() {
        if (recentlyFailed) {
            return wakeup;
        } else {
            return -1;
        }
    }
}
