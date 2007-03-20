/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

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
		return new SimpleFieldSet(true);
	}

	public String getName() {
		return name;
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
		String isDisabledString = fs.get("IsDisabled");
		if(isDisabledString != null) {
			if(!isDisabledString.equals("")) {
				if(Fields.stringToBool(isDisabledString, false)) {
					pn.disablePeer();
				} else {
					pn.enablePeer();
				}
			}
		}
		String isListenOnlyString = fs.get("IsListenOnly");
		if(isListenOnlyString != null) {
			if(!isListenOnlyString.equals("")) {
				pn.setListenOnly(Fields.stringToBool(isListenOnlyString, false));
			}
		}
		String isBurstOnlyString = fs.get("IsBurstOnly");
		if(isBurstOnlyString != null) {
			if(!isBurstOnlyString.equals("")) {
				pn.setBurstOnly(Fields.stringToBool(isBurstOnlyString, false));
			}
		}
		String ignoreSourcePortString = fs.get("IgnoreSourcePort");
		if(ignoreSourcePortString != null) {
			if(!ignoreSourcePortString.equals("")) {
				pn.setIgnoreSourcePort(Fields.stringToBool(ignoreSourcePortString, false));
			}
		}
		String allowLocalAddressesString = fs.get("AllowLocalAddresses");
		if(allowLocalAddressesString != null) {
			if(!allowLocalAddressesString.equals("")) {
				pn.setAllowLocalAddresses(Fields.stringToBool(allowLocalAddressesString, false));
			}
		}
		handler.outputHandler.queue(new Peer(pn, true, true));
	}

}
