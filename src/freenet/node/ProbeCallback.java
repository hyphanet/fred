/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.node;

/** Callback for a locally initiated probe request */
public interface ProbeCallback {
    void onCompleted(String reason, double target, double best, double nearest, long id, short counter,
                     short uniqueCounter, short linearCounter);

    void onTrace(long uid, double target, double nearest, double best, short htl, short counter, double location,
                 long nodeUID, double[] peerLocs, long[] peerUIDs, double[] locsNotVisited, short forkCount,
                 short linearCount, String reason, long prevUID);

    /**
     * Got a RejectedOverload passed down from some upstream node. Note that not all probe request
     * implementations may generate these.
     */
    void onRejectOverload();
}
