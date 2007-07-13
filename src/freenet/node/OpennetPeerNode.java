package freenet.node;

import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.support.SimpleFieldSet;

public class OpennetPeerNode extends PeerNode {

	final OpennetManager opennet;
	
	public OpennetPeerNode(SimpleFieldSet fs, Node node2, NodeCrypto crypto, OpennetManager opennet, PeerManager peers, boolean fromLocal, OutgoingPacketMangler mangler) throws FSParseException, PeerParseException, ReferenceSignatureVerificationException {
		super(fs, node2, crypto, peers, fromLocal, mangler, true);
		this.opennet = opennet;
	}

	public PeerNodeStatus getStatus() {
		return new OpennetPeerNodeStatus(this);
	}

	public boolean isRoutingCompatible() {
		if(!node.isOpennetEnabled()) return false;
		return super.isRoutingCompatible();
	}

	public boolean isOpennet() {
		return true;
	}
}
