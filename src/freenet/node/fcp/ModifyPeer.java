/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.node.DarknetPeerNode;
import freenet.node.Node;
import freenet.node.PeerNode;
import freenet.support.Fields;
import freenet.support.SimpleFieldSet;

public class ModifyPeer extends FCPMessage {

	static final String NAME = "ModifyPeer";
	
	final SimpleFieldSet fs;
	
	public ModifyPeer(SimpleFieldSet fs) {
		this.fs = fs;
	}

	public SimpleFieldSet getFieldSet() {
		return new SimpleFieldSet(true);
	}

	public String getName() {
		return NAME;
	}

	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		if(!handler.hasFullAccess()) {
			throw new MessageInvalidException(ProtocolErrorMessage.ACCESS_DENIED, "ModifyPeer requires full access", fs.get("Identifier"), false);
		}
		String nodeIdentifier = fs.get("NodeIdentifier");
		PeerNode pn = node.getPeerNode(nodeIdentifier);
		if(pn == null) {
			FCPMessage msg = new UnknownNodeIdentifierMessage(nodeIdentifier);
			handler.outputHandler.queue(msg);
			return;
		}
		if(!(pn instanceof DarknetPeerNode)) {
			throw new MessageInvalidException(ProtocolErrorMessage.DARKNET_ONLY, "ModifyPeer only available for darknet peers", fs.get("Identifier"), false);
		}
		DarknetPeerNode dpn = (DarknetPeerNode) pn;
		String isDisabledString = fs.get("IsDisabled");
		if(isDisabledString != null) {
			if(!isDisabledString.equals("")) {
				if(Fields.stringToBool(isDisabledString, false)) {
					dpn.disablePeer();
				} else {
					dpn.enablePeer();
				}
			}
		}
		String isListenOnlyString = fs.get("IsListenOnly");
		if(isListenOnlyString != null) {
			if(!isListenOnlyString.equals("")) {
				dpn.setListenOnly(Fields.stringToBool(isListenOnlyString, false));
			}
		}
		String isBurstOnlyString = fs.get("IsBurstOnly");
		if(isBurstOnlyString != null) {
			if(!isBurstOnlyString.equals("")) {
				dpn.setBurstOnly(Fields.stringToBool(isBurstOnlyString, false));
			}
		}
		String ignoreSourcePortString = fs.get("IgnoreSourcePort");
		if(ignoreSourcePortString != null) {
			if(!ignoreSourcePortString.equals("")) {
				dpn.setIgnoreSourcePort(Fields.stringToBool(ignoreSourcePortString, false));
			}
		}
		String allowLocalAddressesString = fs.get("AllowLocalAddresses");
		if(allowLocalAddressesString != null) {
			if(!allowLocalAddressesString.equals("")) {
				dpn.setAllowLocalAddresses(Fields.stringToBool(allowLocalAddressesString, false));
			}
		}
		handler.outputHandler.queue(new Peer(pn, true, true));
	}

}
