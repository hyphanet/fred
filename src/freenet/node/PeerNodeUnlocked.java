/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.node;

//~--- non-JDK imports --------------------------------------------------------

import freenet.keys.Key;

//~--- JDK imports ------------------------------------------------------------

import java.lang.ref.WeakReference;

/**
 * Methods on PeerNode that don't need any significant locking. Used by FailureTableEntry to
 * guarantee safety. 
 */
interface PeerNodeUnlocked {
    double getLocation();

    long getBootID();

    void offer(Key key);

    WeakReference<? extends PeerNodeUnlocked> getWeakRef();

    public String shortToString();

    boolean isConnected();
}
