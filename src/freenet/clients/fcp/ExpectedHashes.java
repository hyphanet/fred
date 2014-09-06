package freenet.clients.fcp;

import java.io.Serializable;

import freenet.client.events.ExpectedHashesEvent;
import freenet.crypt.HashResult;
import freenet.node.Node;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

public class ExpectedHashes extends FCPMessage implements Serializable {

    private static final long serialVersionUID = 1L;
    final HashResult[] hashes;
	final String identifier;
	final boolean global;
	
	public ExpectedHashes(ExpectedHashesEvent event, String identifier, boolean global) {
		this.identifier = identifier;
		this.global = global;
		this.hashes = event.hashes;
	}
	
    ExpectedHashes(HashResult[] hashes, String identifier, boolean global) {
        this.identifier = identifier;
        this.global = global;
        this.hashes = hashes;
    }
    
	protected ExpectedHashes() {
	    // For serialization.
	    hashes = null;
	    identifier = null;
	    global = false;
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		if(hashes == null) {
			Logger.error(this, "Hashes == null, possibly persistence issue caused prior to build 1411 on "+this);
			return null;
		}
		SimpleFieldSet fs = new SimpleFieldSet(false);
		SimpleFieldSet values = new SimpleFieldSet(false);
		for(HashResult hash : hashes) {
			if(hash == null) {
				Logger.error(this, "Hash == null, possibly persistence issue caused prior to build 1411 on "+this);
				return null;
			}
			values.putOverwrite(hash.type.name(), hash.hashAsHex());
		}
		fs.put("Hashes", values);
		fs.putOverwrite("Identifier", identifier);
		fs.put("Global", global);
		return fs;
	}

	@Override
	public String getName() {
		return "ExpectedHashes";
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new UnsupportedOperationException();
	}

}
