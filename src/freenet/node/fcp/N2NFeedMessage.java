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

public abstract class N2NFeedMessage extends FeedMessage {
    protected final String sourceNodeName;
    protected final long composed, sent, received;

    public N2NFeedMessage(String header, String shortText, String text, short priorityClass, long updatedTime,
                          String sourceNodeName, long composed, long sent, long received) {
        super(header, shortText, text, priorityClass, updatedTime);
        this.sourceNodeName = sourceNodeName;
        this.composed = composed;
        this.sent = sent;
        this.received = received;
    }

    @Override
    public SimpleFieldSet getFieldSet() {
        SimpleFieldSet fs = super.getFieldSet();

        fs.putSingle("SourceNodeName", sourceNodeName);

        if (composed != -1) {
            fs.put("TimeComposed", composed);
        }

        if (sent != -1) {
            fs.put("TimeSent", sent);
        }

        if (received != -1) {
            fs.put("TimeReceived", received);
        }

        return fs;
    }

    @Override
    public void removeFrom(ObjectContainer container) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
        throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE,
                                          getName() + " goes from server to client not the other way around", null,
                                          false);
    }
}
