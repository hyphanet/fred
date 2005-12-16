package freenet.keys;

import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import freenet.crypt.DSAPublicKey;

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
	
	/** Public key hash */
	final byte[] pubKeyHash;
	/** E(H(docname)) (E = encrypt using decrypt key, which only clients know) */
	final byte[] encryptedHashedDocname;
	/** The signature key, if we know it */
	DSAPublicKey pubKey;
	
	public NodeSSK(byte[] pkHash, byte[] ehDocname) {
		super(makeRoutingKey(pkHash, ehDocname));
		this.encryptedHashedDocname = ehDocname;
		this.pubKeyHash = pkHash;
	}
	
	// routingKey = H( E(H(docname)) + H(pubkey) )
	private static byte[] makeRoutingKey(byte[] pkHash, byte[] ehDocname) {
		MessageDigest md256;
		try {
			md256 = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new Error(e);
		}
		md256.update(ehDocname);
		md256.update(pkHash);
		return md256.digest();
	}
	
	// 01 = SSK, 01 = first version of SSK
	public short TYPE = 0x0201;
	
	public void write(DataOutput _index) throws IOException {
        _index.writeShort(TYPE);
        _index.write(encryptedHashedDocname);
        _index.write(pubKeyHash);
	}

	public short getType() {
		return TYPE;
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

}
