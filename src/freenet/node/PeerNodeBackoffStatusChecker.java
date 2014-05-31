/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.node;

//~--- non-JDK imports --------------------------------------------------------

import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

//~--- JDK imports ------------------------------------------------------------

import java.lang.ref.WeakReference;

class PeerNodeBackoffStatusChecker implements Runnable {
    private static volatile boolean logMINOR;

    static {
        Logger.registerLogThresholdCallback(new LogThresholdCallback() {
            @Override
            public void shouldUpdate() {
                logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
            }
        });
    }

    final WeakReference<PeerNode> ref;

    public PeerNodeBackoffStatusChecker(WeakReference<PeerNode> ref) {
        this.ref = ref;
    }

    @Override
    public void run() {
        PeerNode pn = ref.get();

        if (pn == null) {
            return;
        }

        if (pn.cachedRemoved()) {
            if (logMINOR && pn.node.peers.havePeer(pn)) {
                Logger.error(this, "Removed flag is set yet is in peers table?!: " + pn);
            } else {
                return;
            }
        }

        if (!pn.node.peers.havePeer(pn)) {
            if (!pn.cachedRemoved()) {
                Logger.error(this, "Not in peers table but not flagged as removed: " + pn);
            }

            return;
        }

        pn.setPeerNodeStatus(System.currentTimeMillis(), true);
    }
}
