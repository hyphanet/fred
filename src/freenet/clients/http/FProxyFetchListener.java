/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.clients.http;

/** This listener interface can be used to register to an FProxyFetchInProgress to get notified when the fetch's status is changed */
public interface FProxyFetchListener {

    /** Will be called when the fetch's status is changed */
    public void onEvent();
}
