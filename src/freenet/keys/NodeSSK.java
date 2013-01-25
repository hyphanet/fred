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

import com.db4o.ObjectContainer;

import freenet.crypt.DSAPublicKey;
import freenet.crypt.SHA256;
import freenet.store.BlockMetadata;
import freenet.store.GetPubkey;
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
	transient DSAPublicKey pubKey;
	final int hashCode;
	
	public static final int SSK_VERSION = 1;
	
	public static final int PUBKEY_HASH_SIZE = 32;
	public static final int E_H_DOCNAME_SIZE = 32;
	public static final byte BASE_TYPE = 2;
	public static final int FULL_KEY_LENGTH = 66;
	public static final int ROUTING_KEY_LENGTH = 32;
	
	@Override
	public String toString() {
		return super.toString()+":pkh="+HexUtil.bytesToHex(pubKeyHash)+":ehd="+HexUtil.bytesToHex(encryptedHashedDocname);
	}
	
	@Override
	public Key archivalCopy() {
		return new ArchiveNodeSSK(pubKeyHash, encryptedHashedDocname, cryptoAlgorithm);
	}
	
	public NodeSSK(byte[] pkHash, byte[] ehDocname, byte cryptoAlgorithm) {
		super(makeRoutingKey(pkHash, ehDocname));
		this.encryptedHashedDocname = ehDocname;
		this.pubKeyHash = pkHash;
		this.cryptoAlgorithm = cryptoAlgorithm;
		this.pubKey = null;
		if(ehDocname.length != E_H_DOCNAME_SIZE)
			throw new IllegalArgumentException("ehDocname must be "+E_H_DOCNAME_SIZE+" bytes");
		if(pkHash.length != PUBKEY_HASH_SIZE)
			throw new IllegalArgumentException("pubKeyHash must be "+PUBKEY_HASH_SIZE+" bytes");
		hashCode = Fields.hashCode(pkHash) ^ Fields.hashCode(ehDocname);
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
	
    private NodeSSK(NodeSSK key) {
    	super(key);
    	this.cryptoAlgorithm = key.cryptoAlgorithm;
    	this.pubKey = key.pubKey;
    	this.pubKeyHash = key.pubKeyHash.clone();
    	this.encryptedHashedDocname = key.encryptedHashedDocname.clone();
    	this.hashCode = key.hashCode;
    }
    
    @Override
	public Key cloneKey() {
    	return new NodeSSK(this);
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
	
	@Override
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
			throw (AssertionError)new AssertionError("Impossible").initCause(e);
		}
    }

	@Override
	public short getType() {
		return (short) ((BASE_TYPE << 8) + (cryptoAlgorithm & 0xff));
	}

	@Override
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

	@Override
	public boolean equals(Object o) {
		if(o == this) return true;
		if(!(o instanceof NodeSSK)) return false;
		NodeSSK key = (NodeSSK)o;
		if(!Arrays.equals(key.encryptedHashedDocname, encryptedHashedDocname)) return false;
		if(!Arrays.equals(key.pubKeyHash, pubKeyHash)) return false;
		if(!Arrays.equals(key.routingKey, routingKey)) return false;
		// cachedNormalizedDouble and pubKey could be negative/null.
		return true;
	}
	
	@Override
	public int hashCode() {
		return hashCode;
	}
	
    // Not just the routing key, enough data to reconstruct the key (excluding any pubkey needed)
    @Override
	public byte[] getKeyBytes() {
    	return encryptedHashedDocname;
    }
    
    @Override
	public byte[] getFullKey() {
    	byte[] buf = new byte[FULL_KEY_LENGTH];
    	short type = getType();
    	buf[0] = (byte) (type >> 8);
    	buf[1] = (byte) (type & 0xFF);
    	System.arraycopy(encryptedHashedDocname, 0, buf, 2, E_H_DOCNAME_SIZE);
    	System.arraycopy(pubKeyHash, 0, buf, 2+E_H_DOCNAME_SIZE, PUBKEY_HASH_SIZE);
    	return buf;
    }

	public static NodeSSK construct(byte[] buf) throws SSKVerifyException {
		if(buf[0] != 2)
			throw new SSKVerifyException("Unknown type byte "+buf[0]);
		byte cryptoAlgorithm = buf[1];
		if(cryptoAlgorithm != Key.ALGO_AES_PCFB_256_SHA256)
			throw new SSKVerifyException("Unknown crypto algorithm "+buf[1]);
		byte[] encryptedHashedDocname = Arrays.copyOfRange(buf, 2, 2+E_H_DOCNAME_SIZE);
		byte[] pubkeyHash = Arrays.copyOfRange(buf, 2+E_H_DOCNAME_SIZE, 2+E_H_DOCNAME_SIZE+PUBKEY_HASH_SIZE);
		return new NodeSSK(pubkeyHash, encryptedHashedDocname, null, cryptoAlgorithm);
	}

	public boolean grabPubkey(GetPubkey pubkeyCache, boolean canReadClientCache, boolean forULPR, BlockMetadata meta) {
		if(pubKey != null) return false;
		pubKey = pubkeyCache.getKey(pubKeyHash, canReadClientCache, forULPR, meta);
		return pubKey != null;
	}

	public static byte[] routingKeyFromFullKey(byte[] keyBuf) {
		if(keyBuf.length != FULL_KEY_LENGTH) {
			Logger.error(NodeSSK.class, "routingKeyFromFullKey() on buffer length "+keyBuf.length);
		}
		byte[] encryptedHashedDocname = Arrays.copyOfRange(keyBuf, 2, 2+E_H_DOCNAME_SIZE);
		byte[] pubKeyHash = Arrays.copyOfRange(keyBuf, 2+E_H_DOCNAME_SIZE, 2+E_H_DOCNAME_SIZE+PUBKEY_HASH_SIZE);
		return makeRoutingKey(pubKeyHash, encryptedHashedDocname);
	}

	@Override
	public int compareTo(Key arg0) {
		if(arg0 instanceof NodeCHK) return -1;
		NodeSSK key = (NodeSSK) arg0;
		int result = Fields.compareBytes(encryptedHashedDocname, key.encryptedHashedDocname);
		if(result != 0) return result;
		return Fields.compareBytes(pubKeyHash, key.pubKeyHash);
	}
	
	@Override
	public void removeFrom(ObjectContainer container) {
		super.removeFrom(container);
	}

}

final class ArchiveNodeSSK extends NodeSSK {

	public ArchiveNodeSSK(byte[] pubKeyHash, byte[] encryptedHashedDocname, byte cryptoAlgorithm) {
		super(pubKeyHash, encryptedHashedDocname, cryptoAlgorithm);
	}
	
	@Override
	public void setPubKey(DSAPublicKey pubKey2) throws SSKVerifyException {
		throw new UnsupportedOperationException();
	}
	
	@Override
	public boolean grabPubkey(GetPubkey pubkeyCache, boolean canReadClientCache, boolean forULPR, BlockMetadata meta) {
		throw new UnsupportedOperationException();
	}
	
}

