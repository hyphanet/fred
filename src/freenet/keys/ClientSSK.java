/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.keys;

import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.security.MessageDigest;
import java.util.Arrays;

import com.db4o.ObjectContainer;

import freenet.crypt.DSAPublicKey;
import freenet.crypt.SHA256;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.ciphers.Rijndael;
import freenet.support.Fields;
import freenet.support.HexUtil;
import freenet.support.Logger;

/** Client-level SSK, i.e. a low level SSK with the decryption key needed to
 * decrypt the data once it is fetched. Note that you can only use this to
 * *REQUEST* keys, not to *INSERT* them, because it only has the public key
 * and not the private key, @see InsertableClientSSK.
 */
public class ClientSSK extends ClientKey {

	/** Crypto type */
	public final byte cryptoAlgorithm;
	/** Document name */
	public final String docName;
	/** Public key */
	protected transient DSAPublicKey pubKey;
	/** Public key hash */
	public final byte[] pubKeyHash;
	/** Encryption key */
	public final byte[] cryptoKey;
	/** Encrypted hashed docname */
	public final byte[] ehDocname;
	private final int hashCode;
	
	public static final int CRYPTO_KEY_LENGTH = 32;
	public static final int EXTRA_LENGTH = 5;
	
	private ClientSSK(ClientSSK key) {
		this.cryptoAlgorithm = key.cryptoAlgorithm;
		this.docName = new String(key.docName);
		if(key.pubKey != null)
			this.pubKey = key.pubKey.cloneKey();
		else
			this.pubKey = null;
		pubKeyHash = new byte[key.pubKeyHash.length];
		System.arraycopy(key.pubKeyHash, 0, pubKeyHash, 0, pubKeyHash.length);
		cryptoKey = new byte[key.cryptoKey.length];
		System.arraycopy(key.cryptoKey, 0, cryptoKey, 0, key.cryptoKey.length);
		ehDocname = new byte[key.ehDocname.length];
		System.arraycopy(key.ehDocname, 0, ehDocname, 0, key.ehDocname.length);
		hashCode = Fields.hashCode(pubKeyHash) ^ Fields.hashCode(cryptoKey) ^ Fields.hashCode(ehDocname) ^ docName.hashCode();
	}
	
	public ClientSSK(String docName, byte[] pubKeyHash, byte[] extras, DSAPublicKey pubKey, byte[] cryptoKey) throws MalformedURLException {
		this.docName = docName;
		this.pubKey = pubKey;
		this.pubKeyHash = pubKeyHash;
		if(docName == null)
			throw new MalformedURLException("No document name.");
		if(extras == null)
			throw new MalformedURLException("No extra bytes in SSK - maybe a 0.5 key?");
		if(extras.length < 5)
			throw new MalformedURLException("Extra bytes too short: "+extras.length+" bytes");
		this.cryptoAlgorithm = extras[2];
		if(cryptoAlgorithm != Key.ALGO_AES_PCFB_256_SHA256)
			throw new MalformedURLException("Unknown encryption algorithm "+cryptoAlgorithm);
		if(!Arrays.equals(extras, getExtraBytes()))
			throw new MalformedURLException("Wrong extra bytes");
		if(pubKeyHash.length != NodeSSK.PUBKEY_HASH_SIZE)
			throw new MalformedURLException("Pubkey hash wrong length: "+pubKeyHash.length+" should be "+NodeSSK.PUBKEY_HASH_SIZE);
		if(cryptoKey.length != CRYPTO_KEY_LENGTH)
			throw new MalformedURLException("Decryption key wrong length: "+cryptoKey.length+" should be "+CRYPTO_KEY_LENGTH);
		MessageDigest md = SHA256.getMessageDigest();
		try {
			if (pubKey != null) {
				byte[] pubKeyAsBytes = pubKey.asBytes();
				md.update(pubKeyAsBytes);
				byte[] otherPubKeyHash = md.digest();
				if (!Arrays.equals(otherPubKeyHash, pubKeyHash))
					throw new IllegalArgumentException();
			}
			this.cryptoKey = cryptoKey;
			try {
				md.update(docName.getBytes("UTF-8"));
			} catch (UnsupportedEncodingException e) {
				throw new Error("Impossible: JVM doesn't support UTF-8: " + e, e);
			}
			byte[] buf = md.digest();
			try {
				Rijndael aes = new Rijndael(256, 256);
				aes.initialize(cryptoKey);
				aes.encipher(buf, buf);
				ehDocname = buf;
			} catch (UnsupportedCipherException e) {
				throw new Error(e);
			}
		} finally {
			SHA256.returnMessageDigest(md);
		}
		if(ehDocname == null)
			throw new NullPointerException();
		hashCode = Fields.hashCode(pubKeyHash) ^ Fields.hashCode(cryptoKey) ^ Fields.hashCode(ehDocname) ^ docName.hashCode();
	}
	
	public ClientSSK(FreenetURI origURI) throws MalformedURLException {
		this(origURI.getDocName(), origURI.getRoutingKey(), origURI.getExtra(), null, origURI.getCryptoKey());
		if(!origURI.getKeyType().equalsIgnoreCase("SSK"))
			throw new MalformedURLException();
	}
	
	public synchronized void setPublicKey(DSAPublicKey pubKey) {
		if((this.pubKey != null) && (this.pubKey != pubKey) && !this.pubKey.equals(pubKey))
			throw new IllegalArgumentException("Cannot reassign: was "+this.pubKey+" now "+pubKey);
		byte[] newKeyHash = pubKey.asBytesHash();
		if(!Arrays.equals(newKeyHash, pubKeyHash))
			throw new IllegalArgumentException("New pubKey hash does not match pubKeyHash: "+HexUtil.bytesToHex(newKeyHash)+" ( "+HexUtil.bytesToHex(pubKey.asBytesHash())+" != "+HexUtil.bytesToHex(pubKeyHash)+" for "+pubKey);
		this.pubKey = pubKey;
		this.cachedNodeKey = null;
	}
	
	@Override
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
		extra[2] = cryptoAlgorithm;
		extra[3] = (byte) (KeyBlock.HASH_SHA256 >> 8);
		extra[4] = (byte) KeyBlock.HASH_SHA256;
		return extra;
	}
	
	static final byte[] STANDARD_EXTRA = getExtraBytes(Key.ALGO_AES_PCFB_256_SHA256);
	
	public static byte[] internExtra(byte[] buf) {
		if(Arrays.equals(buf, STANDARD_EXTRA)) return STANDARD_EXTRA;
		return buf;
	}

	private transient Key cachedNodeKey;
	
	@Override
	public Key getNodeKey(boolean cloneKey) {
		try {
			Key nodeKey;
			synchronized(this) {
				if(ehDocname == null)
					throw new NullPointerException();
				if(pubKeyHash == null)
					throw new NullPointerException();
				if (cachedNodeKey == null || cachedNodeKey.getKeyBytes() == null || cachedNodeKey.getRoutingKey() == null)
					cachedNodeKey = new NodeSSK(pubKeyHash, ehDocname, pubKey, cryptoAlgorithm);
				nodeKey = cachedNodeKey;
			}
			return cloneKey ? nodeKey.cloneKey() : nodeKey;
		} catch (SSKVerifyException e) {
			Logger.error(this, "Have already verified and yet it fails!: "+e);
			throw (AssertionError)new AssertionError("Have already verified and yet it fails!").initCause(e);
		}
	}

	public DSAPublicKey getPubKey() {
		return pubKey;
	}

	@Override
	public String toString() {
		return "ClientSSK:"+getURI().toString();
	}

	@Override
	public ClientKey cloneKey() {
		return new ClientSSK(this);
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		container.delete(this);
	}
	
	@Override
	public int hashCode() {
		return hashCode;
	}
	
	@Override
	public boolean equals(Object o) {
		if(!(o instanceof ClientSSK)) return false;
		ClientSSK key = (ClientSSK) o;
		if(cryptoAlgorithm != key.cryptoAlgorithm) return false;
		if(!docName.equals(key.docName)) return false;
		if(!Arrays.equals(pubKeyHash, key.pubKeyHash)) return false;
		if(!Arrays.equals(cryptoKey, key.cryptoKey)) return false;
		if(!Arrays.equals(ehDocname, key.ehDocname)) return false;
		return true;
	}
}
