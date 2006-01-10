package freenet.keys;

import java.io.DataInput;
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
	
	static final int PUBKEY_HASH_SIZE = 32;
	static final int E_H_DOCNAME_SIZE = 32;
	
	public NodeSSK(byte[] pkHash, byte[] ehDocname, DSAPublicKey pubKey) {
		super(makeRoutingKey(pkHash, ehDocname));
		this.encryptedHashedDocname = ehDocname;
		this.pubKeyHash = pkHash;
		this.pubKey = pubKey;
		if(ehDocname.length != E_H_DOCNAME_SIZE)
			throw new IllegalArgumentException("ehDocname must be "+E_H_DOCNAME_SIZE+" bytes");
		if(pkHash.length != PUBKEY_HASH_SIZE)
			throw new IllegalArgumentException("pubKeyHash must be "+PUBKEY_HASH_SIZE+" bytes");
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
	public static short TYPE = 0x0201;
	
	public void write(DataOutput _index) throws IOException {
        _index.writeShort(TYPE);
        _index.write(encryptedHashedDocname);
        _index.write(pubKeyHash);
	}

    public static Key readSSK(DataInput raf) throws IOException {
        byte[] buf = new byte[E_H_DOCNAME_SIZE];
        raf.readFully(buf);
        byte[] buf2 = new byte[PUBKEY_HASH_SIZE];
        raf.readFully(buf2);
        return new NodeSSK(buf2, buf, null);
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

	public byte[] getPubKeyHash() {
		return pubKeyHash;
	}

	public void setPubKey(DSAPublicKey pubKey2) {
		if(pubKey == pubKey2) return;
		if(pubKey != null) throw new IllegalStateException("Already assigned pubkey to different value! Old="+pubKey.writeAsField()+", new="+pubKey2.writeAsField());
		this.pubKey = pubKey2;
	}

}
