/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import freenet.keys.FreenetURI;
import freenet.keys.InsertableClientSSK;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class GenerateSSKMessage extends FCPMessage {

	static final String NAME = "GenerateSSK";
	final String identifier;
	
	GenerateSSKMessage(SimpleFieldSet fs) {
		identifier = fs.get("Identifier");
	}
	
	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		if(identifier != null)
			fs.putSingle("Identifier", identifier);
		return fs;
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
    	InsertableClientSSK key = InsertableClientSSK.createRandom(node.random, "");
    	FreenetURI insertURI = key.getInsertURI();
    	FreenetURI requestURI = key.getURI();
    	SSKKeypairMessage msg = new SSKKeypairMessage(insertURI, requestURI, identifier);
    	handler.send(msg);
	}

}
