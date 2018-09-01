/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class NodeData extends FCPMessage {
	static final String name = "NodeData";
	
	final Node node;
	final boolean giveOpennetRef;
	final boolean withPrivate;
	final boolean withVolatile;
	final String identifier;
	
	public NodeData(Node node, boolean giveOpennetRef, boolean withPrivate, boolean withVolatile, String identifier) {
		this.node = node;
		this.giveOpennetRef = giveOpennetRef;
		this.withPrivate = withPrivate;
		this.withVolatile = withVolatile;
		this.identifier = identifier;
	}
	
	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs;
		if(giveOpennetRef) {
			if(withPrivate) {
				fs = node.exportOpennetPrivateFieldSet();
			} else {
				fs = node.exportOpennetPublicFieldSet();
			}
		} else {
			if(withPrivate) {
				fs = node.exportDarknetPrivateFieldSet();
			} else {
				fs = node.exportDarknetPublicFieldSet();
			}
		}
		if(withVolatile) {
			SimpleFieldSet vol = node.exportVolatileFieldSet();
			if(!vol.isEmpty()) {
			 	fs.put("volatile", vol);
			}
		}
		if(identifier != null)
			fs.putSingle("Identifier", identifier);
		return fs;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "NodeData goes from server to client not the other way around", identifier, false);
	}

}
