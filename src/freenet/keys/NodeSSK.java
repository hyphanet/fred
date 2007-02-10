/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.keys;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;

import freenet.crypt.DSAPublicKey;
import freenet.crypt.SHA256;
import freenet.support.Fields;
import freenet.support.HexUtil;
import freenet.support.Logger;

/**
 * An SSK is a Signed Subspace Key.
 * { pubkey, cryptokey, filename } -> document, basically.
 * To insert you need the private key corresponding to pubkey.
 * KSKs are implemented via SSKs.
 * 
 * This is just the key, so we have the hash of the pubkey, the entire cryptokey,
 * and the entire filename.
 */
public class NodeSSK extends Key {
	
	/** Crypto algorithm */
	final byte cryptoAlgorithm;
	/** Public key hash */
	final byte[] pubKeyHash;
	/** E(H(docname)) (E = encrypt using decrypt key, which only clients know) */
	final byte[] encryptedHashedDocname;
	/** The signature key, if we know it */
	DSAPublicKey pubKey;
	final int hashCode;
	
	static final int SSK_VERSION = 1;
	
	static final int PUBKEY_HASH_SIZE = 32;
	static final int E_H_DOCNAME_SIZE = 32;
	static final byte BASE_TYPE = 2;
	
	public String toString() {
		return super.toString()+":pkh="+HexUtil.bytesToHex(pubKeyHash)+":ehd="+HexUtil.bytesToHex(encryptedHashedDocname);
	}
	
	public NodeSSK(byte[] pkHash, byte[] ehDocname, DSAPublicKey pubKey, byte cryptoAlgorithm) throws SSKVerifyException {
		super(makeRoutingKey(pkHash, ehDocname));
		this.encryptedHashedDocname = ehDocname;
		this.pubKeyHash = pkHash;
		this.cryptoAlgorithm = cryptoAlgorithm;
		this.pubKey = pubKey;
		if(pubKey != null) {
			byte[] hash = SHA256.digest(pubKey.asBytes());
			if(!Arrays.equals(hash, pkHash))
				throw new SSKVerifyException("Invalid pubKey: wrong hash");
		}
		if(ehDocname.length != E_H_DOCNAME_SIZE)
			throw new IllegalArgumentException("ehDocname must be "+E_H_DOCNAME_SIZE+" bytes");
		if(pkHash.length != PUBKEY_HASH_SIZE)
			throw new IllegalArgumentException("pubKeyHash must be "+PUBKEY_HASH_SIZE+" bytes");
		hashCode = Fields.hashCode(pkHash) ^ Fields.hashCode(ehDocname);
	}
	
	// routingKey = H( E(H(docname)) + H(pubkey) )
	private static byte[] makeRoutingKey(byte[] pkHash, byte[] ehDocname) {
		MessageDigest md256 = SHA256.getMessageDigest();
		md256.update(ehDocname);
		md256.update(pkHash);
		byte[] key = md256.digest();
		SHA256.returnMessageDigest(md256);
		return key;
	}
	
	public void write(DataOutput _index) throws IOException {
        _index.writeShort(getType());
        _index.write(encryptedHashedDocname);
        _index.write(pubKeyHash);
	}

    public static Key readSSK(DataInput raf, byte cryptoAlgorithm) throws IOException {
        byte[] buf = new byte[E_H_DOCNAME_SIZE];
        raf.readFully(buf);
        byte[] buf2 = new byte[PUBKEY_HASH_SIZE];
        raf.readFully(buf2);
        try {
			return new NodeSSK(buf2, buf, null, cryptoAlgorithm);
		} catch (SSKVerifyException e) {
			IllegalStateException impossible = 
				new IllegalStateException("Impossible: "+e);
			impossible.initCause(e);
			throw impossible;
		}
    }

	public short getType() {
		return (short) (0x0200 + (cryptoAlgorithm & 0xff));
	}

	public void writeToDataOutputStream(DataOutputStream stream) throws IOException {
		write(stream);
	}

	/**
	 * @return True if we know the pubkey.
	 */
	public boolean hasPubKey() {
		return pubKey != null;
	}
	
	/**
	 * @return The public key, *if* we know it. Otherwise null.
	 */
	public DSAPublicKey getPubKey() {
		return pubKey;
	}

	public byte[] getPubKeyHash() {
		return pubKeyHash;
	}

	public void setPubKey(DSAPublicKey pubKey2) throws SSKVerifyException {
		if(pubKey == pubKey2) return;
		if(pubKey2 == null) return;
		if((pubKey == null) || !pubKey2.equals(pubKey)) {
			if(pubKey2 != null) {
				byte[] newPubKeyHash = SHA256.digest(pubKey2.asBytes());
				if(Arrays.equals(pubKeyHash, newPubKeyHash)) {
					if(pubKey != null) {
						// same hash, yet different keys!
						Logger.error(this, "Found SHA-256 collision or something... WTF?");
						throw new SSKVerifyException("Invalid new pubkey: "+pubKey2+" old pubkey: "+pubKey);
					} 
					// Valid key, assign.
				} else {
					throw new SSKVerifyException("New pubkey has invalid hash");
				}
			}
			pubKey = pubKey2;
		}
	}

	public boolean equals(Object o) {
		if(!(o instanceof NodeSSK)) return false;
		NodeSSK key = (NodeSSK)o;
		if(!Arrays.equals(key.encryptedHashedDocname, encryptedHashedDocname)) return false;
		if(!Arrays.equals(key.pubKeyHash, pubKeyHash)) return false;
		if(!Arrays.equals(key.routingKey, routingKey)) return false;
		// cachedNormalizedDouble and pubKey could be negative/null.
		return true;
	}
	
	public int hashCode() {
		return hashCode;
	}
	
}
