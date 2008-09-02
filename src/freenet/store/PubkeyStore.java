package freenet.store;

import java.io.IOException;

import freenet.crypt.CryptFormatException;
import freenet.crypt.DSAPublicKey;
import freenet.keys.KeyVerifyException;
import freenet.keys.PubkeyVerifyException;
import freenet.support.Logger;

public class PubkeyStore extends StoreCallback {

	public boolean collisionPossible() {
		return false;
	}

	public StorableBlock construct(byte[] data, byte[] headers, byte[] routingKey,
			byte[] fullKey) throws KeyVerifyException {
		if(data == null) throw new PubkeyVerifyException("Need data to construct pubkey");
		try {
			return DSAPublicKey.create(data);
		} catch (CryptFormatException e) {
			throw new PubkeyVerifyException(e);
		}
	}

	public DSAPublicKey fetch(byte[] hash, boolean dontPromote) throws IOException {
		return (DSAPublicKey) store.fetch(hash, null, dontPromote);
	}
	
	final private static byte[] empty = new byte[0];
	
	public void put(byte[] hash, DSAPublicKey key) throws IOException {
		try {
			store.put(key, key.getRoutingKey(), key.getFullKey(), key.asPaddedBytes(), empty, false);
		} catch (KeyCollisionException e) {
			Logger.error(this, "Impossible for PubkeyStore: "+e, e);
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

	public boolean constructNeedsKey() {
		return false;
	}

	public byte[] routingKeyFromFullKey(byte[] keyBuf) {
		return keyBuf;
	}

}
