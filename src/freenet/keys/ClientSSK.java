package freenet.keys;

import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import freenet.crypt.DSAPublicKey;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.ciphers.Rijndael;
import freenet.support.Logger;

public class ClientSSK extends ClientKey {

	/** Document name */
	public final String docName;
	/** Public key */
	protected DSAPublicKey pubKey;
	/** Public key hash */
	public final byte[] pubKeyHash;
	/** Encryption key */
	public final byte[] cryptoKey;
	/** Encrypted hashed docname */
	public final byte[] ehDocname;
	
	static final int CRYPTO_KEY_LENGTH = 32;
	public static final int EXTRA_LENGTH = 5;
	
	public ClientSSK(String docName, byte[] pubKeyHash, byte[] extras, DSAPublicKey pubKey, byte[] cryptoKey) throws MalformedURLException {
		this.docName = docName;
		this.pubKey = pubKey;
		this.pubKeyHash = pubKeyHash;
		if(!Arrays.equals(extras, getExtraBytes()))
			throw new MalformedURLException("Wrong extra bytes");
		if(pubKeyHash.length != NodeSSK.PUBKEY_HASH_SIZE)
			throw new MalformedURLException("Pubkey hash wrong length: "+pubKeyHash.length+" should be "+NodeSSK.PUBKEY_HASH_SIZE);
		if(cryptoKey.length != CRYPTO_KEY_LENGTH)
			throw new MalformedURLException("Decryption key wrong length: "+cryptoKey.length+" should be "+CRYPTO_KEY_LENGTH);
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
		this(origURI.getDocName(), origURI.getRoutingKey(), origURI.getExtra(), null, origURI.getCryptoKey());
		if(!origURI.getKeyType().equalsIgnoreCase("SSK"))
			throw new MalformedURLException();
	}
	
	public void setPublicKey(DSAPublicKey pubKey) {
		if((this.pubKey != null) && (this.pubKey != pubKey) && !this.pubKey.equals(pubKey))
			throw new IllegalArgumentException("Cannot reassign: was "+this.pubKey+" now "+pubKey);
		this.pubKey = pubKey;
	}
	
	public FreenetURI getURI() {
		return new FreenetURI("SSK", docName, pubKeyHash, cryptoKey, getExtraBytes());
	}
	
	protected static final byte[] getExtraBytes() {
		// 5 bytes.
		byte[] extra = new byte[5];

		short cryptoAlgorithm = Key.ALGO_AES_PCFB_256_SHA256;
		
		extra[0] = NodeSSK.SSK_VERSION;
		extra[1] = (byte) (cryptoAlgorithm >> 8);
		extra[2] = (byte) cryptoAlgorithm;
		extra[3] = (byte) (KeyBlock.HASH_SHA256 >> 8);
		extra[4] = (byte) KeyBlock.HASH_SHA256;
		return extra;
	}

	public Key getNodeKey() {
		try {
			return new NodeSSK(pubKeyHash, ehDocname, pubKey);
		} catch (SSKVerifyException e) {
			IllegalStateException x = new IllegalStateException("Have already verified and yet it fails!: "+e);
			Logger.error(this, "Have already verified and yet it fails!: "+e);
			x.initCause(e);
			throw x;
		}
	}

	public DSAPublicKey getPubKey() {
		return pubKey;
	}

	public String toString() {
		return "ClientSSK:"+getURI().toString();
	}
}
