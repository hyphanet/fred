package freenet.node.fcp;

import java.io.File;

import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.node.PeerNode;
import freenet.support.SimpleFieldSet;

public class Peer extends FCPMessage {

	static final String name = "Peer";
	
	final PeerNode pn;
	final boolean withMetadata;
	
	public Peer(PeerNode pn, boolean withMetadata) {
		this.pn = pn;
		this.withMetadata = withMetadata;
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = pn.exportFieldSet();
		if(withMetadata) {
			SimpleFieldSet meta = pn.exportMetadataFieldSet();
			if(!meta.isEmpty()) {
			 	fs.put("metadata", meta);
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
