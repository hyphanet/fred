/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.node;

//~--- non-JDK imports --------------------------------------------------------

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;

/**
 * Normally only implemented by SendableGet's.
 * @author toad
 */
public interface SupportsBulkCallFailure {

    /** Process a whole batch of failures at once. */
    public abstract void onFailure(BulkCallFailureItem[] items, ObjectContainer container, ClientContext context);
}
