package freenet.keys;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import net.i2p.util.NativeBigInteger;
import freenet.crypt.DSA;
import freenet.crypt.DSAPublicKey;
import freenet.crypt.DSASignature;
import freenet.support.HexUtil;

/**
 * SSKBlock. Contains a full fetched key. Can do a node-level verification. Can 
 * decode original data when fed a ClientSSK.
 */
public class SSKBlock implements KeyBlock {
	// how much of the headers we compare in order to consider two
	// SSKBlocks equal - necessary because the last 64 bytes need not
	// be the same for the same data and the same key (see comments below)
	private static final int HEADER_COMPARE_TO = 71;
	final byte[] data;
	final byte[] headers;
	/** The index of the first byte of encrypted fields in the headers, after E(H(docname)) */
	final int headersOffset;
	/* HEADERS FORMAT:
	 * 2 bytes - hash ID
	 * 2 bytes - symmetric cipher ID
	 * 32 bytes - E(H(docname))
	 * ENCRYPTED WITH E(H(docname)) AS IV:
	 *  32 bytes - H(decrypted data), = data decryption key
	 *  2 bytes - data length + metadata flag
	 *  2 bytes - data compression algorithm or -1
	 * IMPLICIT - hash of data
	 * IMPLICIT - hash of remaining fields, including the implicit hash of data
	 * 
	 * SIGNATURE ON THE ABOVE HASH:
	 *  32 bytes - signature: R (unsigned bytes)
	 *  32 bytes - signature: S (unsigned bytes)
	 * 
	 * PLUS THE PUBKEY:
	 *  Pubkey
	 *  Group
	 */
	final NodeSSK nodeKey;
	final DSAPublicKey pubKey;
    final short hashIdentifier;
    final short symCipherIdentifier;
    
    public static final short DATA_LENGTH = 1024;
    
    static final short SIG_R_LENGTH = 32;
    static final short SIG_S_LENGTH = 32;
    static final short E_H_DOCNAME_LENGTH = 32;
    static public final short TOTAL_HEADERS_LENGTH = 2 + SIG_R_LENGTH + SIG_S_LENGTH + 2 + 
    	E_H_DOCNAME_LENGTH + ClientSSKBlock.DATA_DECRYPT_KEY_LENGTH + 2 + 2;
    
    static final short ENCRYPTED_HEADERS_LENGTH = 36;
    
    public boolean equals(Object o) {
    	if(!(o instanceof SSKBlock)) return false;
    	SSKBlock block = (SSKBlock)o;

    	if(!block.pubKey.equals(pubKey)) return false;
    	if(!block.nodeKey.equals(nodeKey)) return false;
    	if(block.headersOffset != headersOffset) return false;
    	if(block.hashIdentifier != hashIdentifier) return false;
    	if(block.symCipherIdentifier != symCipherIdentifier) return false;
    	// only compare some of the headers (see top)
    	for (int i = 0; i < HEADER_COMPARE_TO; i++) {
    		if (block.headers[i] != headers[i]) return false;
    	}
    	//if(!Arrays.equals(block.headers, headers)) return false;
    	if(!Arrays.equals(block.data, data)) return false;
    	return true;
    }
    
    public int hashCode(){
    	return super.hashCode();
    }
    
	/**
	 * Initialize, and verify data, headers against key. Provided
	 * key must have a pubkey, or we throw.
	 */
	public SSKBlock(byte[] data, byte[] headers, NodeSSK nodeKey, boolean dontVerify) throws SSKVerifyException {
		if(headers.length != TOTAL_HEADERS_LENGTH)
			throw new IllegalArgumentException("Headers.length="+headers.length+" should be "+TOTAL_HEADERS_LENGTH);
		this.data = data;
		this.headers = headers;
		this.nodeKey = nodeKey;
		if(data.length != DATA_LENGTH)
			throw new SSKVerifyException("Data length wrong: "+data.length+" should be "+DATA_LENGTH);
		this.pubKey = nodeKey.getPubKey();
		if(pubKey == null)
			throw new SSKVerifyException("PubKey was null from "+nodeKey);
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new Error(e);
        }
        // Now verify it
        hashIdentifier = (short)(((headers[0] & 0xff) << 8) + (headers[1] & 0xff));
        if(hashIdentifier != HASH_SHA256)
            throw new SSKVerifyException("Hash not SHA-256");
        int x = 2;
		symCipherIdentifier = (short)(((headers[x] & 0xff) << 8) + (headers[x+1] & 0xff));
		x+=2;
		// Then E(H(docname))
		byte[] ehDocname = new byte[E_H_DOCNAME_LENGTH];
		System.arraycopy(headers, x, ehDocname, 0, ehDocname.length);
		x += E_H_DOCNAME_LENGTH;
		headersOffset = x; // is index to start of encrypted headers
		x += ENCRYPTED_HEADERS_LENGTH;
		// Extract the signature
		byte[] bufR = new byte[SIG_R_LENGTH];
		byte[] bufS = new byte[SIG_S_LENGTH];
		if(x+SIG_R_LENGTH+SIG_S_LENGTH > headers.length)
			throw new SSKVerifyException("Headers too short: "+headers.length+" should be at least "+x+SIG_R_LENGTH+SIG_S_LENGTH);
		if(!dontVerify)
			System.arraycopy(headers, x, bufR, 0, SIG_R_LENGTH);
		x+=SIG_R_LENGTH;
		if(!dontVerify)
			System.arraycopy(headers, x, bufS, 0, SIG_S_LENGTH);
		x+=SIG_S_LENGTH;
		// Compute the hash on the data
		if(!dontVerify) {
			md.update(data);
			byte[] dataHash = md.digest();
			// All headers up to and not including the signature
			md.update(headers, 0, headersOffset + ENCRYPTED_HEADERS_LENGTH);
			// Then the implicit data hash
			md.update(dataHash);
			// Makes the implicit overall hash
			byte[] overallHash = md.digest();
			// Now verify it
			NativeBigInteger r = new NativeBigInteger(1, bufR);
			NativeBigInteger s = new NativeBigInteger(1, bufS);
			if(!DSA.verify(pubKey, new DSASignature(r, s), new NativeBigInteger(1, overallHash))) {
				throw new SSKVerifyException("Signature verification failed for node-level SSK");
			}
		}
		if(!Arrays.equals(ehDocname, nodeKey.encryptedHashedDocname))
			throw new SSKVerifyException("E(H(docname)) wrong - wrong key?? \nfrom headers: "+HexUtil.bytesToHex(ehDocname)+"\nfrom key:     "+HexUtil.bytesToHex(nodeKey.encryptedHashedDocname));
	}

	public Key getKey() {
		return nodeKey;
	}

	public byte[] getRawHeaders() {
		return headers;
	}

	public byte[] getRawData() {
		return data;
	}

	public DSAPublicKey getPubKey() {
		return pubKey;
	}

}
