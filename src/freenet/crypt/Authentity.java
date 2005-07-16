package freenet.crypt;

import java.math.BigInteger;

import freenet.crypt.CryptoElement;
import freenet.crypt.CryptoKey;

/**
 * An interface for the Authentication elements (private keys) in Freenet nodes
 * 
 * @author oskar
 */
public interface Authentity {

	/**
	 * Returns the signature of the byte digest.
	 */
	CryptoElement sign(byte[] digest);

	CryptoElement sign(BigInteger b);

	/**
	 * Get a FieldSet containing hex encodings of the primitives.
	 */
	FieldSet getFieldSet();

	/**
	 * @return the Cryptographic private key of this authentity
	 */
	CryptoKey getKey();

	/**
	 * @return the appropriate Identity for this Authentity
	 */
	Identity getIdentity();
}
