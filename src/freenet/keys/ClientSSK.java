package freenet.keys;

import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import freenet.crypt.DSAPublicKey;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.ciphers.Rijndael;

public class ClientSSK extends ClientKey {

	/** Document name */
	public final String docName;
	/** Public key */
	public DSAPublicKey pubKey;
	/** Public key hash */
	public final byte[] pubKeyHash;
	/** Encryption key */
	public final byte[] cryptoKey;
	/** Encrypted hashed docname */
	public final byte[] ehDocname;
	
	static final int CRYPTO_KEY_LENGTH = 32;
	
	public ClientSSK(String docName, byte[] pubKeyHash, DSAPublicKey pubKey, byte[] cryptoKey) {
		this.docName = docName;
		this.pubKey = pubKey;
		this.pubKeyHash = pubKeyHash;
		MessageDigest md;
		try {
			md = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new Error(e);
		}
		if(pubKey != null) {
			byte[] pubKeyAsBytes = pubKey.asBytes();
			md.update(pubKeyAsBytes);
			byte[] otherPubKeyHash = md.digest();
			if(!Arrays.equals(otherPubKeyHash, pubKeyHash))
				throw new IllegalArgumentException();
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
	
	public ClientSSK(FreenetURI origURI) throws MalformedURLException {
		this(origURI.getDocName(), origURI.getRoutingKey(), null, origURI.getCryptoKey());
		if(!origURI.getKeyType().equalsIgnoreCase("SSK"))
			throw new MalformedURLException();
	}
	
	public void setPublicKey(DSAPublicKey pubKey) {
		if(this.pubKey != null && this.pubKey != pubKey && !this.pubKey.equals(pubKey))
			throw new IllegalArgumentException("Cannot reassign: was "+this.pubKey+" now "+pubKey);
		this.pubKey = pubKey;
	}
	
	public FreenetURI getURI() {
		return new FreenetURI("SSK", docName, pubKeyHash, cryptoKey, null);
	}

	public Key getNodeKey() {
		return new NodeSSK(pubKeyHash, ehDocname, pubKey);
	}

}
