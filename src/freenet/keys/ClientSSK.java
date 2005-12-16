package freenet.keys;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import freenet.crypt.DSAPublicKey;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.ciphers.Rijndael;

public class ClientSSK extends ClientKey {

	/** Document name */
	public final String docName;
	/** Public key */
	public final DSAPublicKey pubKey;
	/** Public key hash */
	public final byte[] pubKeyHash;
	/** Encryption key */
	public final byte[] cryptoKey;
	/** Encrypted hashed docname */
	public final byte[] ehDocname;
	
	public ClientSSK(String docName, DSAPublicKey pubKey, byte[] cryptoKey) {
		this.docName = docName;
		this.pubKey = pubKey;
		byte[] pubKeyAsBytes = pubKey.asBytes();
		MessageDigest md;
		try {
			md = MessageDigest.getInstance("SHA-256");
			md.update(pubKeyAsBytes);
			pubKeyHash = md.digest();
		} catch (NoSuchAlgorithmException e) {
			throw new Error(e);
		}
		this.cryptoKey = cryptoKey;
		try {
			md.update(docName.getBytes("UTF-8"));
		} catch (UnsupportedEncodingException e) {
			throw new Error(e);
		}
		byte[] buf = md.digest();
		try {
			Rijndael aes = new Rijndael(256,256);
			aes.initialize(cryptoKey);
			aes.encipher(buf, buf);
			ehDocname = buf;
		} catch (UnsupportedCipherException e) {
			throw new Error(e);
		}
		
	}
	
	public FreenetURI getURI() {
		return new FreenetURI("SSK", docName, pubKeyHash, cryptoKey, null);
	}

	public Key getNodeKey() {
		return new NodeSSK(pubKeyHash, ehDocname);
	}

}
