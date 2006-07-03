package freenet.node;

import freenet.crypt.DSAPublicKey;

/**
 * Interface for a DSA public key lookup service.
 */
public interface DSAPublicKeyDatabase {
	
	/**
	 * Lookup a key by its hash.
	 * @param hash
	 * @return The key, or null.
	 */
	public DSAPublicKey lookupKey(byte[] hash);

}
