package freenet.store;

import freenet.crypt.CryptFormatException;
import freenet.crypt.DSAPublicKey;
import freenet.keys.KeyVerifyException;
import freenet.keys.PubkeyVerifyException;

public class PubkeyStore extends StoreCallback {

	public boolean collisionPossible() {
		return false;
	}

	StorableBlock construct(byte[] data, byte[] headers, byte[] routingKey,
			byte[] fullKey) throws KeyVerifyException {
		if(data == null) throw new PubkeyVerifyException("Need data to construct pubkey");
		try {
			return DSAPublicKey.create(data);
		} catch (CryptFormatException e) {
			throw new PubkeyVerifyException(e);
		}
	}

	public int dataLength() {
		return DSAPublicKey.PADDED_SIZE;
	}

	public int fullKeyLength() {
		return DSAPublicKey.HASH_LENGTH;
	}

	public int headerLength() {
		return 0;
	}

	public int routingKeyLength() {
		return DSAPublicKey.HASH_LENGTH;
	}

	public boolean storeFullKeys() {
		return false;
	}

}
