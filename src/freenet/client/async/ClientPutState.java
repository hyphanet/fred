/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.client.async;

//~--- non-JDK imports --------------------------------------------------------

import com.db4o.ObjectContainer;

import freenet.client.InsertException;

/**
 * ClientPutState
 *
 * Represents a state in the insert process.
 */
public interface ClientPutState {

    /** Get the BaseClientPutter responsible for this request state. */
    public abstract BaseClientPutter getParent();

    /** Cancel the request. */
    public abstract void cancel(ObjectContainer container, ClientContext context);

    /** Schedule the request. */
    public abstract void schedule(ObjectContainer container, ClientContext context) throws InsertException;

    /**
     * Get the token, an object which is passed around with the insert and may be
     * used by callers.
     */
    public Object getToken();

    /**
     * Once the callback has finished with this fetch, it will call removeFrom() to instruct the fetch
     * to remove itself and all its subsidiary objects from the database.
     * @param container
     */
    public void removeFrom(ObjectContainer container, ClientContext context);
}
