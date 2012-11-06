package freenet.node.fcp;

import com.db4o.ObjectContainer;

import freenet.client.events.ExpectedHashesEvent;
import freenet.crypt.HashResult;
import freenet.node.Node;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

public class ExpectedHashes extends FCPMessage {

	final HashResult[] hashes;
	final String identifier;
	final boolean global;
	
	public ExpectedHashes(ExpectedHashesEvent event, String identifier, boolean global) {
		this.identifier = identifier;
		this.global = global;
		this.hashes = event.hashes;
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
			if(hashes == null) {
				Logger.error(this, "Hash == null, possibly persistence issue caused prior to build 1411 on "+this);
				return null;
			}
			values.putOverwrite(hash.type.name(), HexUtil.bytesToHex(hash.result));
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
	public void removeFrom(ObjectContainer container) {
		if(hashes != null) {
			for(HashResult res : hashes) {
				if(res == null) continue; // Pre-1411 persistence issue
				container.activate(res, Integer.MAX_VALUE);
				res.removeFrom(container);
			}
		}
		container.delete(this);
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new UnsupportedOperationException();
	}

}
