package freenet.node;

import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.support.SimpleFieldSet;

public abstract class BaseDarknetPeerNode extends PeerNode {
	
	/** Name of this node */
	String myName;

	public BaseDarknetPeerNode(SimpleFieldSet fs, Node node2, NodeCrypto crypto, PeerManager peers, boolean fromLocal, boolean fromAnonymousInitiator, OutgoingPacketMangler mangler, boolean isOpennet) throws FSParseException, PeerParseException, ReferenceSignatureVerificationException {
		super(fs, node2, crypto, peers, fromLocal, fromAnonymousInitiator, mangler, isOpennet);
		String name = fs.get("myName");
		if(name == null) throw new FSParseException("No name");
		myName = name;
	}

	public synchronized String getName() {
		return myName;
	}

	@Override
	protected synchronized boolean innerProcessNewNoderef(SimpleFieldSet fs, boolean forARK, boolean forDiffNodeRef, boolean forFullNodeRef) throws FSParseException {
		boolean changedAnything = super.innerProcessNewNoderef(fs, forARK, forDiffNodeRef, forFullNodeRef);
		String name = fs.get("myName");
		if(name == null && forFullNodeRef) throw new FSParseException("No name in full noderef");
		if(name != null && !name.equals(myName)) {
			changedAnything = true;
			myName = name;
		}
		return changedAnything;
	}

	@Override
	public synchronized SimpleFieldSet exportFieldSet() {
		SimpleFieldSet fs = super.exportFieldSet();
		fs.putSingle("myName", getName());
		return fs;
	}

	@Override
	public String userToString() {
		return ""+getPeer()+" : "+getName();
	}
	
	@Override
	public boolean isDarknet() {
		return true;
	}

	@Override
	public boolean isOpennet() {
		return false;
	}

	@Override
	public boolean isSeed() {
		return false;
	}

}
