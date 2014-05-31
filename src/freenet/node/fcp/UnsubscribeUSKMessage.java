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

public class UnsubscribeUSKMessage extends FCPMessage {
    public static final String NAME = "UnsubscribeUSK";
    private final String identifier;

    public UnsubscribeUSKMessage(SimpleFieldSet fs) throws MessageInvalidException {
        this.identifier = fs.get("Identifier");

        if (identifier == null) {
            throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No Identifier!", null, false);
        }
    }

    @Override
    public SimpleFieldSet getFieldSet() {
        throw new UnsupportedOperationException();
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
        handler.unsubscribeUSK(identifier);
    }
}
