/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.client.async;

//~--- non-JDK imports --------------------------------------------------------

import com.db4o.ObjectContainer;

/**
 * A client process. Something that initiates requests, and can cancel them. FCP, FProxy, and the
 * GlobalPersistentClient, implement this somewhere.
 */
public interface ClientBaseCallback {

    /**
     * Called when freenet.async thinks that the request should be serialized to disk, if it is a
     * persistent request.
     */
    public void onMajorProgress(ObjectContainer container);
}
