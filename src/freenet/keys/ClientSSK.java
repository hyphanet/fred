/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.keys;

import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.security.MessageDigest;
import java.util.Arrays;

import freenet.crypt.DSAPublicKey;
import freenet.crypt.SHA256;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.ciphers.Rijndael;
import freenet.support.HexUtil;
import freenet.support.Logger;

public class ClientSSK extends ClientKey {

	/** Crypto type */
	public final byte cryptoAlgorithm;
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
		if(extras == null)
			throw new MalformedURLException("No extra bytes in SSK - maybe a 0.5 key?");
		if(extras.length < 5)
			throw new MalformedURLException("Extra bytes too short: "+extras.length+" bytes");
		this.cryptoAlgorithm = extras[2];
		if(!(cryptoAlgorithm == Key.ALGO_AES_PCFB_256_SHA256 ||
				cryptoAlgorithm == Key.ALGO_INSECURE_AES_PCFB_256_SHA256))
			throw new MalformedURLException("Unknown encryption algorithm "+cryptoAlgorithm);
		if(!Arrays.equals(extras, getExtraBytes()))
			throw new MalformedURLException("Wrong extra bytes");
		if(pubKeyHash.length != NodeSSK.PUBKEY_HASH_SIZE)
			throw new MalformedURLException("Pubkey hash wrong length: "+pubKeyHash.length+" should be "+NodeSSK.PUBKEY_HASH_SIZE);
		if(cryptoKey.length != CRYPTO_KEY_LENGTH)
			throw new MalformedURLException("Decryption key wrong length: "+cryptoKey.length+" should be "+CRYPTO_KEY_LENGTH);
		MessageDigest md = SHA256.getMessageDigest();
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
			Rijndael aes = new Rijndael(256,256,cryptoAlgorithm == Key.ALGO_INSECURE_AES_PCFB_256_SHA256);
			aes.initialize(cryptoKey);
			aes.encipher(buf, buf);
			ehDocname = buf;
		} catch (UnsupportedCipherException e) {
			throw new Error(e);
		}
		SHA256.returnMessageDigest(md);
	}
	
	public ClientSSK(FreenetURI origURI) throws MalformedURLException {
		this(origURI.getDocName(), origURI.getRoutingKey(), origURI.getExtra(), null, origURI.getCryptoKey());
		if(!origURI.getKeyType().equalsIgnoreCase("SSK"))
			throw new MalformedURLException();
	}
	
	public void setPublicKey(DSAPublicKey pubKey) {
		if((this.pubKey != null) && (this.pubKey != pubKey) && !this.pubKey.equals(pubKey))
			throw new IllegalArgumentException("Cannot reassign: was "+this.pubKey+" now "+pubKey);
		byte[] newKeyHash = pubKey.asBytesHash();
		if(!Arrays.equals(newKeyHash, pubKeyHash))
			throw new IllegalArgumentException("New pubKey hash does not match pubKeyHash: "+HexUtil.bytesToHex(newKeyHash)+" ( "+HexUtil.bytesToHex(pubKey.asBytesHash())+" != "+HexUtil.bytesToHex(pubKeyHash)+" for "+pubKey);
		this.pubKey = pubKey;
	}
	
	public FreenetURI getURI() {
		return new FreenetURI("SSK", docName, pubKeyHash, cryptoKey, getExtraBytes());
	}

	protected final byte[] getExtraBytes() {
		return getExtraBytes(cryptoAlgorithm);
	}
	
	protected static byte[] getExtraBytes(byte cryptoAlgorithm) {
		// 5 bytes.
		byte[] extra = new byte[5];

		extra[0] = NodeSSK.SSK_VERSION;
		extra[1] = 0; // 0 = fetch (public) URI; 1 = insert (private) URI
		extra[2] = (byte) cryptoAlgorithm;
		extra[3] = (byte) (KeyBlock.HASH_SHA256 >> 8);
		extra[4] = (byte) KeyBlock.HASH_SHA256;
		return extra;
	}

	public Key getNodeKey() {
		try {
			return new NodeSSK(pubKeyHash, ehDocname, pubKey, cryptoAlgorithm);
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
