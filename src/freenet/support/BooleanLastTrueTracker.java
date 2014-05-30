/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.support;

/** Tracks when a condition was last true, e.g. whether a peer is connected. Self-synchronized. */
public class BooleanLastTrueTracker {
    private boolean isTrue;
    private long timeLastTrue;

    public BooleanLastTrueTracker() {
        isTrue = false;
        timeLastTrue = -1;
    }

    /** Initialise with a time last connected */
    public BooleanLastTrueTracker(long lastTrue) {
        isTrue = false;
        timeLastTrue = lastTrue;
    }

    public synchronized boolean isTrue() {
        return isTrue;
    }

    public synchronized boolean set(boolean value, long now) {
        if (value == isTrue) {
            return value;
        }

        if (!isTrue) {
            timeLastTrue = now;
        }

        isTrue = value;

        return !value;
    }

    public synchronized long getTimeLastTrue(long now) {
        if (isTrue) {
            return now;
        } else {
            return timeLastTrue;
        }
    }
}
