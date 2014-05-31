/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.node.fcp;

//~--- non-JDK imports --------------------------------------------------------

import com.db4o.ObjectContainer;

public interface RequestCompletionCallback {

    /**
     * Callback called when a request succeeds.
     */
    public void notifySuccess(ClientRequest req, ObjectContainer container);

    /**
     * Callback called when a request fails
     */
    public void notifyFailure(ClientRequest req, ObjectContainer container);

    /**
     * Callback when a request is removed
     */
    public void onRemove(ClientRequest req, ObjectContainer container);
}
