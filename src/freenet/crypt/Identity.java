package freenet.crypt;

import freenet.crypt.CryptoKey;
import freenet.crypt.CryptoElement;
import java.math.BigInteger;

/** A unique identifier for a NodeReference, or a client.
 * @author tavin
 */
public interface Identity extends Comparable {

    /**
     * Return the fingerprint of the identity object
     */
    byte[] fingerprint();
    
    /**
     * Same as above, but the fingerprint is returned as base 16 String.
     */
    String fingerprintToString();

    /**
     * Verify a signature.
     * @param sig     The signature in field format (hex string)
     * @param digest  The digest of the signed data.
     */
    boolean verify(String sig, BigInteger digest);

    /**
     * Verify a signature.
     * @param sig     The signature as an object (if it the object is the wrong
     *                type for this key, false should be returned).
     * @param digest  The digest of the signed data.
     */
    boolean verify(CryptoElement sig, BigInteger digest);

    /**
     * Get a FieldSet containing hex encodings of the primitives.
     */
    FieldSet getFieldSet();

    /**
     * Returns the CryptoKey of this Identity
     */
    CryptoKey getKey();
}
