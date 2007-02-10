/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.node.Node;
import freenet.node.fcp.FCPMessage;
import freenet.support.SimpleFieldSet;

public class ShutdownMessage extends FCPMessage{
	public final static String name = "Shutdown";
	
	public ShutdownMessage() throws MessageInvalidException {
	}

	public SimpleFieldSet getFieldSet() {
		return new SimpleFieldSet(true);
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node) {
		FCPMessage msg = new ProtocolErrorMessage(ProtocolErrorMessage.SHUTTING_DOWN,true,"The node is shutting down","Node",false);
		handler.outputHandler.queue(msg);
		node.exit("Received FCP shutdown message");
	}	
}