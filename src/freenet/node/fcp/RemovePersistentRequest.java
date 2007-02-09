/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.node.Node;
import freenet.support.*;

/**
 * Client telling node to remove a (completed or not) persistent request.
 */
public class RemovePersistentRequest extends FCPMessage {

	final static String name = "RemovePersistentRequest";
	
	final String identifier;
	final boolean global;
	
	public RemovePersistentRequest(SimpleFieldSet fs) throws MessageInvalidException {
		this.global = Fields.stringToBool(fs.get("Global"), false);
		this.identifier = fs.get("Identifier");
		if(identifier == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "Must have Identifier", null, global);
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet();
		fs.putSingle("Identifier", identifier);
		return fs;
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		FCPClient client = global ? handler.server.globalClient : handler.getClient();
        ClientRequest req = client.getRequest(identifier);
        if(req==null){
            Logger.error(this, "Huh ? the request is null!");
            return;
        }
		client.removeByIdentifier(identifier, true);
        req.requestWasRemoved();
	}
}
