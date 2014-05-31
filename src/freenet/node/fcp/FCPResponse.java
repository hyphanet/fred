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

/**
 * FCP message sent from the node to the client.
 */
public abstract class FCPResponse extends FCPMessage {
    protected final SimpleFieldSet fs;

    /**
     * @param fcpIdentifier FCP-level identifier for pairing requests and responses.
     *                      If null the field is omitted.
     */
    public FCPResponse(String fcpIdentifier) {
        fs = new SimpleFieldSet(true);
        fs.putOverwrite(IDENTIFIER, fcpIdentifier);
    }

    @Override
    public SimpleFieldSet getFieldSet() {
        return fs;
    }

    @Override
    public abstract String getName();

    @Override
    public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
        throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE,
                                          getName() + " is a reply from the node; the client should not send it.",
                                          null, false);
    }

    @Override
    public void removeFrom(ObjectContainer container) {
        throw new UnsupportedOperationException();
    }
}
