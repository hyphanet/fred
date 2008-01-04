package freenet.node;

import freenet.crypt.DSAPublicKey;

public interface GetPubkey {

	/**
	 * Look up a cached public key by its hash.
	 */
	public abstract DSAPublicKey getKey(byte[] hash);

}