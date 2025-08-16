/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.keys;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Arrays;

import freenet.crypt.DSAPublicKey;
import freenet.crypt.Global;
import freenet.crypt.SHA256;
import freenet.support.Fields;
import freenet.support.HexUtil;
import freenet.support.Logger;
import org.bouncycastle.crypto.params.DSAPublicKeyParameters;
import org.bouncycastle.crypto.signers.DSASigner;

/**
 * SSKBlock. Contains a full fetched key. Can do a node-level verification. Can 
 * decode original data when fed a ClientSSK.
 */
public class SSKBlock implements KeyBlock {
	private static volatile boolean logMINOR;
	
	static {
		Logger.registerClass(SSKBlock.class);
	}
	
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
    final int hashCode;
    
    public static final short DATA_LENGTH = 1024;
    /* Maximum length of compressed payload */
	public static final int MAX_COMPRESSED_DATA_LENGTH = DATA_LENGTH - 2;
    
    static final short SIG_R_LENGTH = 32;
    static final short SIG_S_LENGTH = 32;
    static final short E_H_DOCNAME_LENGTH = 32;
    static public final short TOTAL_HEADERS_LENGTH = 2 + SIG_R_LENGTH + SIG_S_LENGTH + 2 + 
    	E_H_DOCNAME_LENGTH + ClientSSKBlock.DATA_DECRYPT_KEY_LENGTH + 2 + 2;
    
    static final short ENCRYPTED_HEADERS_LENGTH = 36;
    
    @Override
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
    
    @Override
	public int hashCode(){
    	return hashCode;
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
		if(x+SIG_R_LENGTH+SIG_S_LENGTH > headers.length)
			throw new SSKVerifyException("Headers too short: "+headers.length+" should be at least "+x+SIG_R_LENGTH+SIG_S_LENGTH);
		// Compute the hash on the data
		if(!dontVerify || logMINOR) {	// force verify on log minor
			byte[] bufR = new byte[SIG_R_LENGTH];
			byte[] bufS = new byte[SIG_S_LENGTH];
			
			System.arraycopy(headers, x, bufR, 0, SIG_R_LENGTH);
			x+=SIG_R_LENGTH;
			System.arraycopy(headers, x, bufS, 0, SIG_S_LENGTH);
			x+=SIG_S_LENGTH;

			byte[] overallHash;
			MessageDigest md = SHA256.getMessageDigest();
			md.update(data);
			byte[] dataHash = md.digest();
			// All headers up to and not including the signature
			md.update(headers, 0, headersOffset + ENCRYPTED_HEADERS_LENGTH);
			// Then the implicit data hash
			md.update(dataHash);
			// Makes the implicit overall hash
			overallHash = md.digest();
			
			// Now verify it
			BigInteger r = new BigInteger(1, bufR);
			BigInteger s = new BigInteger(1, bufS);
			DSASigner dsa = new DSASigner();
			dsa.init(false, new DSAPublicKeyParameters(pubKey.getY(), Global.getDSAgroupBigAParameters()));

			// We probably don't need to try both here...
			// but that's what the legacy code was doing...
			// @see comments in Global before touching it
			if(!(dsa.verifySignature(Global.truncateHash(overallHash), r, s) ||
			     dsa.verifySignature(overallHash, r, s))
			  ) {
				if (dontVerify)
					Logger.error(this, "DSA verification failed with dontVerify!!!!");
				throw new SSKVerifyException("Signature verification failed for node-level SSK");
			}
		} // x isn't verified otherwise so no need to += SIG_R_LENGTH + SIG_S_LENGTH
		if(!Arrays.equals(ehDocname, nodeKey.encryptedHashedDocname))
			throw new SSKVerifyException("E(H(docname)) wrong - wrong key?? \nfrom headers: "+HexUtil.bytesToHex(ehDocname)+"\nfrom key:     "+HexUtil.bytesToHex(nodeKey.encryptedHashedDocname));
		hashCode = Fields.hashCode(data) ^ Fields.hashCode(headers) ^ nodeKey.hashCode() ^ pubKey.hashCode() ^ hashIdentifier;
	}

	@Override
	public NodeSSK getKey() {
		return nodeKey;
	}

	@Override
	public byte[] getRawHeaders() {
		return headers;
	}

	@Override
	public byte[] getRawData() {
		return data;
	}

	public DSAPublicKey getPubKey() {
		return pubKey;
	}

	@Override
	public byte[] getPubkeyBytes() {
		return pubKey.asBytes();
	}

	@Override
	public byte[] getFullKey() {
		return getKey().getFullKey();
	}

	@Override
	public byte[] getRoutingKey() {
		return getKey().getRoutingKey();
	}
	
}
