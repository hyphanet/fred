/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.client.async;

//~--- non-JDK imports --------------------------------------------------------

import com.db4o.ObjectContainer;

import freenet.node.SendableRequest;

public interface SendableRequestSet {
    public SendableRequest[] listRequests(ObjectContainer container);

    public boolean addRequest(SendableRequest req, ObjectContainer container);

    public boolean removeRequest(SendableRequest req, ObjectContainer container);

    public void removeFrom(ObjectContainer container);
}
