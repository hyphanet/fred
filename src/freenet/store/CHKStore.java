package freenet.store;

import freenet.keys.CHKBlock;
import freenet.keys.KeyVerifyException;
import freenet.keys.NodeCHK;

public class CHKStore extends StoreCallback {

	public boolean collisionPossible() {
		return false;
	}

	public StorableBlock construct(byte[] data, byte[] headers,
			byte[] routingKey, byte[] fullKey) throws KeyVerifyException {
		return CHKBlock.construct(data, headers);
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
		return false;
	}

}
