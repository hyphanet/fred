/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import com.db4o.ObjectContainer;

import freenet.node.Node;
import freenet.node.PeerNode;
import freenet.support.SimpleFieldSet;

public class PeerMessage extends FCPMessage {
	static final String name = "Peer";
	
	final PeerNode pn;
	final boolean withMetadata;
	final boolean withVolatile;
	final String identifier;
	
	public PeerMessage(PeerNode pn, boolean withMetadata, boolean withVolatile, String identifier) {
		this.pn = pn;
		this.withMetadata = withMetadata;
		this.withVolatile = withVolatile;
		this.identifier = identifier;
	}
	
	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = pn.exportFieldSet();
		if(withMetadata) {
			SimpleFieldSet meta = pn.exportMetadataFieldSet(System.currentTimeMillis());
			if(!meta.isEmpty()) {
			 	fs.put("metadata", meta);
			}
		}
		if(withVolatile) {
			SimpleFieldSet vol = pn.exportVolatileFieldSet();
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
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "Peer goes from server to client not the other way around", null, false);
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		container.delete(this);
	}

}
