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

public class SentPeerMessage extends FCPMessage {
    public static final String NAME = "SentPeer";
    public final String identifier;
    public final int nodeStatus;

    public SentPeerMessage(String identifier, int nodeStatus) {
        this.identifier = identifier;
        this.nodeStatus = nodeStatus;
    }

    @Override
    public SimpleFieldSet getFieldSet() {
        SimpleFieldSet fs = new SimpleFieldSet(true);

        fs.putSingle("Identifier", identifier);
        fs.put("NodeStatus", nodeStatus);

        // TODO Textual description of the node status?
        return fs;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void removeFrom(ObjectContainer container) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
        throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE,
                                          getName() + " goes from server to client not the other way around",
                                          identifier, false);
    }
}
