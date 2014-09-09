package freenet.node.fcp;

import freenet.client.events.ExpectedHashesEvent;
import freenet.crypt.HashResult;

public class ExpectedHashes extends FCPMessage {

	final HashResult[] hashes;
	final String identifier;
	final boolean global;
	
	public ExpectedHashes(ExpectedHashesEvent event, String identifier, boolean global) {
		this.identifier = identifier;
		this.global = global;
		this.hashes = event.hashes;
	}

}
