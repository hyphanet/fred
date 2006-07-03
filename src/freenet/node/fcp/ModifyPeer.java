package freenet.node.fcp;

import java.io.File;

import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.node.PeerNode;
import freenet.support.Fields;
import freenet.support.SimpleFieldSet;

public class ModifyPeer extends FCPMessage {

	static final String name = "ModifyPeer";
	
	final SimpleFieldSet fs;
	
	public ModifyPeer(SimpleFieldSet fs) {
		this.fs = fs;
	}

	public SimpleFieldSet getFieldSet() {
		return new SimpleFieldSet(false);
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		String nodeIdentifier = fs.get("NodeIdentifier");
		PeerNode pn = node.getPeerNode(nodeIdentifier);
		if(pn == null) {
			ProtocolErrorMessage msg = new ProtocolErrorMessage(ProtocolErrorMessage.NO_SUCH_NODE_IDENTIFIER, false, null, nodeIdentifier);
			handler.outputHandler.queue(msg);
			return;
		}
		String isDisabledString = fs.get("IsDisabled");
		if(isDisabledString != null) {
			if(!isDisabledString.equals("")) {
				if(Fields.stringToBool(isDisabledString, false)) {
					pn.disablePeer();
				} else {
					pn.enablePeer();
				}
			} else {
				ProtocolErrorMessage msg = new ProtocolErrorMessage(ProtocolErrorMessage.MESSAGE_PARSE_ERROR, false, "IsDisabled had no value", nodeIdentifier);
				handler.outputHandler.queue(msg);
				return;
			}
		}
		String isListenOnlyString = fs.get("IsListenOnly");
		if(isListenOnlyString != null) {
			if(!isListenOnlyString.equals("")) {
				pn.setListenOnly(Fields.stringToBool(isListenOnlyString, false));
			} else {
				ProtocolErrorMessage msg = new ProtocolErrorMessage(ProtocolErrorMessage.MESSAGE_PARSE_ERROR, false, "IsListenOnly had no value", nodeIdentifier);
				handler.outputHandler.queue(msg);
				return;
			}
		}
		handler.outputHandler.queue(new Peer(pn, true, true));
	}

}
