package freenet.node.fcp;

import freenet.node.Node;
import freenet.node.PeerNode;
import freenet.support.SimpleFieldSet;

public class Peer extends FCPMessage {
	static final String name = "Peer";
	
	final PeerNode pn;
	final boolean withMetadata;
	final boolean withVolatile;
	
	public Peer(PeerNode pn, boolean withMetadata, boolean withVolatile) {
		this.pn = pn;
		this.withMetadata = withMetadata;
		this.withVolatile = withVolatile;
	}
	
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = pn.exportFieldSet();
		if(withMetadata) {
			SimpleFieldSet meta = pn.exportMetadataFieldSet();
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
		return fs;
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "Peer goes from server to client not the other way around", null);
	}

}
