package freenet.keys;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import net.i2p.util.NativeBigInteger;

import freenet.crypt.DSA;
import freenet.crypt.DSAPublicKey;
import freenet.crypt.DSASignature;
import freenet.support.Bucket;
import freenet.support.BucketFactory;

/**
 * SSKBlock. Contains a full fetched key. Can do a node-level verification. Can 
 * decode original data when fed a ClientSSK.
 */
public class SSKBlock implements KeyBlock {

	final byte[] data;
	final byte[] headers;
	/** The index of the first non-signature-related byte in the headers */
	final int headersOffset;
	/* HEADERS FORMAT:
	 * 2 bytes - hash ID
	 * SIGNATURE ON THE BELOW HASH:
	 *  20 bytes - signature: R (unsigned bytes)
	 *  20 bytes - signature: S (unsigned bytes)
	 * IMPLICIT - hash of remaining fields, including the implicit hash of data
	 * IMPLICIT - hash of data
	 * 2 bytes - symmetric cipher ID
	 * 32 bytes - E(H(docname))
	 * ENCRYPTED WITH E(H(docname)) AS IV:
	 *  32 bytes - H(decrypted data), = data decryption key
	 *  2 bytes - data length + metadata flag
	 *  2 bytes - data compression algorithm or -1
	 * 
	 * PLUS THE PUBKEY:
	 *  Pubkey
	 *  Group
	 */
	final NodeSSK nodeKey;
	final DSAPublicKey pubKey;
    final short hashIdentifier;
    
    static final short DATA_LENGTH = 1024;
    
    static final short SIG_R_LENGTH = 20;
    static final short SIG_S_LENGTH = 20;
	
	/**
	 * Initialize, and verify data, headers against key. Provided
	 * key must have a pubkey, or we throw.
	 */
	public SSKBlock(byte[] data, byte[] headers, NodeSSK nodeKey) throws SSKVerifyException {
		this.data = data;
		this.headers = headers;
		this.nodeKey = nodeKey;
		this.pubKey = nodeKey.getPubKey();
		if(data.length != DATA_LENGTH)
			throw new SSKVerifyException("Data length wrong: "+data.length+" should be "+DATA_LENGTH);
		if(pubKey == null)
			throw new SSKVerifyException("PubKey was null from "+nodeKey);
        if(headers.length < 2) throw new IllegalArgumentException("Too short: "+headers.length);
        hashIdentifier = (short)(((headers[0] & 0xff) << 8) + (headers[1] & 0xff));
        if(hashIdentifier != HASH_SHA256)
            throw new SSKVerifyException("Hash not SHA-256");
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new Error(e);
        }
        // Now verify it
		// Extract the signature
		byte[] bufR = new byte[SIG_R_LENGTH];
		byte[] bufS = new byte[SIG_S_LENGTH];
		int x = 2;
		if(x+SIG_R_LENGTH+SIG_S_LENGTH > headers.length)
			throw new SSKVerifyException("Headers too short: "+headers.length+" should be at least "+x+SIG_R_LENGTH+SIG_S_LENGTH);
		System.arraycopy(headers, x, bufR, 0, SIG_R_LENGTH);
		x+=SIG_R_LENGTH;
		System.arraycopy(headers, x, bufS, 0, SIG_S_LENGTH);
		x+=SIG_S_LENGTH;
		// Compute the hash on the data
		md.update(data);
		byte[] dataHash = md.digest();
		md.update(dataHash);
		md.update(headers, x, headers.length - x);
		byte[] overallHash = md.digest();
		// Now verify it
		NativeBigInteger r = new NativeBigInteger(1, bufR);
		NativeBigInteger s = new NativeBigInteger(1, bufS);
		if(!DSA.verify(pubKey, new DSASignature(r, s), new NativeBigInteger(1, overallHash))) {
			throw new SSKVerifyException("Signature verification failed for node-level SSK");
		}
		headersOffset = x;
	}
	
	public Bucket decode(ClientKey key, BucketFactory factory, int maxLength) throws KeyDecodeException, IOException {
		
		// TODO Auto-generated method stub
		return null;
	}

}
