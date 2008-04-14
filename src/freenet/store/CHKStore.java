package freenet.store;

import java.io.IOException;

import freenet.keys.CHKBlock;
import freenet.keys.CHKVerifyException;
import freenet.keys.KeyVerifyException;
import freenet.keys.NodeCHK;
import freenet.support.Logger;

public class CHKStore extends StoreCallback {

	public boolean collisionPossible() {
		return false;
	}

	public StorableBlock construct(byte[] data, byte[] headers,
			byte[] routingKey, byte[] fullKey) throws KeyVerifyException {
		if(data == null || headers == null) throw new CHKVerifyException("Need either data and headers");
		return CHKBlock.construct(data, headers);
	}

	public CHKBlock fetch(NodeCHK chk, boolean dontPromote) throws IOException {
		return (CHKBlock) store.fetch(chk.getRoutingKey(), null, dontPromote);
	}
	
	public void put(CHKBlock b) throws IOException {
		NodeCHK key = (NodeCHK) b.getKey();
		try {
			store.put(b, key.getRoutingKey(), key.getRoutingKey(), b.getRawData(), b.getRawHeaders(), false);
		} catch (KeyCollisionException e) {
			Logger.error(this, "Impossible for CHKStore: "+e, e);
		}
	}
	
	public int dataLength() {
		return CHKBlock.DATA_LENGTH;
	}

	public int fullKeyLength() {
		return NodeCHK.FULL_KEY_LENGTH;
	}

	public int headerLength() {
		return CHKBlock.TOTAL_HEADERS_LENGTH;
	}

	public int routingKeyLength() {
		return NodeCHK.KEY_LENGTH;
	}

	public boolean storeFullKeys() {
		// Worth the extra two file descriptors, because if we have the keys we can do lazy 
		// reconstruction i.e. don't construct each block, just transcode from the .keys file
		// straight into the database.
		return true;
	}

	public boolean constructNeedsKey() {
		return false;
	}

	public byte[] routingKeyFromFullKey(byte[] keyBuf) {
		return NodeCHK.routingKeyFromFullKey(keyBuf);
	}

}
