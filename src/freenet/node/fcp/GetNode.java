/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import com.db4o.ObjectContainer;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class GetNode extends FCPMessage {

	final boolean giveOpennetRef;
	final boolean withPrivate;
	final boolean withVolatile;
	static final String NAME = "GetNode";
	final String identifier;
	
	public GetNode(SimpleFieldSet fs) {
		giveOpennetRef = fs.getBoolean("GiveOpennetRef", false);
		withPrivate = fs.getBoolean("WithPrivate", false);
		withVolatile = fs.getBoolean("WithVolatile", false);
		identifier = fs.get("Identifier");
		fs.removeValue("Identifier");
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
		if(!handler.hasFullAccess()) {
			throw new MessageInvalidException(ProtocolErrorMessage.ACCESS_DENIED, "GetNode requires full access", identifier, false);
		}
		handler.outputHandler.queue(new NodeData(node, giveOpennetRef, withPrivate, withVolatile, identifier));
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		container.delete(this);
	}
	
}
