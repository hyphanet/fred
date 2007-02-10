/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.node.*;
import freenet.support.*;

/**
 * Node answer message after a ModifyPerPersistentRequest message from client. 
 */
public class PersistentRequestModifiedMessage extends FCPMessage {

    private final String ident;
    private final boolean global;

    private final SimpleFieldSet fs;
    
    private PersistentRequestModifiedMessage(String identifier, boolean global) {
        // remembered for the MessageInvalidException only
        this.ident = identifier;
        this.global = global;

        fs = new SimpleFieldSet(true);
        fs.putSingle("Identifier", identifier);
        if(global) fs.putSingle("Global", "true");
    }

    public PersistentRequestModifiedMessage(String identifier, boolean global, short priorityClass) {
        this(identifier, global);
        fs.putSingle("PriorityClass", Short.toString(priorityClass));
    }

    public PersistentRequestModifiedMessage(String identifier, boolean global, String clientToken) {
        this(identifier, global);
        fs.putSingle("ClientToken", clientToken);
    }

    public PersistentRequestModifiedMessage(String identifier, boolean global, short priorityClass, String clientToken) {
        this(identifier, global);
        fs.putSingle("PriorityClass", Short.toString(priorityClass));
        fs.putSingle("ClientToken", clientToken);
    }

    public SimpleFieldSet getFieldSet() {
        return fs;
    }

    public String getName() {
        return "PersistentRequestModified";
    }

    public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
        throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "PersistentRequestModified goes from server to client not the other way around", ident, global);
    }
}
