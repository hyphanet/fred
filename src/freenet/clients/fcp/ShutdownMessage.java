/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class ShutdownMessage extends FCPMessage{
	public final static String NAME = "Shutdown";
	// No point having an Identifier really...?
	
	public ShutdownMessage() throws MessageInvalidException {
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		return null;
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		if(!handler.hasFullAccess()) {
			throw new MessageInvalidException(ProtocolErrorMessage.ACCESS_DENIED, "Shutdown requires full access", null, false);
		}
		FCPMessage msg = new ProtocolErrorMessage(ProtocolErrorMessage.SHUTTING_DOWN,true,"The node is shutting down","Node",false);
		handler.send(msg);
		node.exit("Received FCP shutdown message");
	}

}