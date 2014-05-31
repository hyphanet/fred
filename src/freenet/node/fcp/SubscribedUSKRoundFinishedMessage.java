/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.node.fcp;

//~--- non-JDK imports --------------------------------------------------------

import com.db4o.ObjectContainer;

import freenet.node.Node;

import freenet.support.SimpleFieldSet;

public class SubscribedUSKRoundFinishedMessage extends FCPMessage {
    final String identifier;

    SubscribedUSKRoundFinishedMessage(String id) {
        identifier = id;
    }

    @Override
    public SimpleFieldSet getFieldSet() {
        SimpleFieldSet fs = new SimpleFieldSet(true);

        fs.putSingle("Identifier", identifier);

        return fs;
    }

    @Override
    public String getName() {
        return "SubscribedUSKRoundFinished";
    }

    @Override
    public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeFrom(ObjectContainer container) {
        throw new UnsupportedOperationException();
    }
}
