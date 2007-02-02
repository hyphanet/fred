/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.node.*;
import freenet.support.*;

/**
 * Node answer message after a RemovePersistentRequest message from client. 
 */
public class PersistentRequestRemovedMessage extends FCPMessage {

    private final String ident;
    private final boolean global;
    
    public PersistentRequestRemovedMessage(String identifier, boolean global) {
        this.ident = identifier;
        this.global = global;
    }

    public SimpleFieldSet getFieldSet() {
        SimpleFieldSet fs = new SimpleFieldSet();
        fs.put("Identifier", ident);
        if(global) fs.put("Global", "true");
        return fs;
    }

    public String getName() {
        return "PersistentRequestRemoved";
    }

    public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
        throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "PersistentRequestRemoved goes from server to client not the other way around", ident, global);
    }
}
