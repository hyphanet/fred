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
    
    private final short priorityClass;
    private final String clientToken;
    
    public PersistentRequestModifiedMessage(String identifier, boolean global, short priorityClass) {
        this(identifier, global, priorityClass, null); // clientToken not set
    }

    public PersistentRequestModifiedMessage(String identifier, boolean global, String clientToken) {
        this(identifier, global, (short)(-1), clientToken); // priorityClass not set
    }

    public PersistentRequestModifiedMessage(String identifier, boolean global, short priorityClass, String clientToken) {
        this.ident = identifier;
        this.global = global;
        this.priorityClass = priorityClass;
        this.clientToken = clientToken;
    }

    public SimpleFieldSet getFieldSet() {
        final SimpleFieldSet fs = new SimpleFieldSet(true);
        fs.putSingle("Identifier", ident);
        if(global) fs.putSingle("Global", "true");
        if(priorityClass >= 0)   fs.putSingle("PriorityClass", Short.toString(priorityClass));
        if(clientToken != null ) fs.putSingle("ClientToken", clientToken);
        return fs;
    }

    public String getName() {
        return "PersistentRequestModified";
    }

    public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
        throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "PersistentRequestModified goes from server to client not the other way around", ident, global);
    }
}
