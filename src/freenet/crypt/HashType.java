/**
 *
 */
package freenet.crypt;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.bitpedia.util.TigerTree;

public enum HashType {
	// warning: keep in sync with Util.mdProviders!
	SHA1(1, 20),
	MD5(2, 16),
	SHA256(4, "SHA-256", 32),
	SHA384(8, "SHA-384", 48),
	SHA512(16, "SHA-512", 64),
	ED2K(32, null, 16),
	TTH(64, null, 24);

	/** Bitmask for aggregation. */
	public final int bitmask;
	/** Name for MessageDigest purposes. Can contain dashes. */
	public final String javaName;
	public final int hashLength;

	HashType(int bitmask, int hashLength) {
		this.bitmask = bitmask;
		this.javaName = super.name();
		this.hashLength = hashLength;
	}

	HashType(int bitmask, String name, int hashLength) {
		this.bitmask = bitmask;
		this.javaName = name;
		this.hashLength = hashLength;
	}

	public MessageDigest get() throws NoSuchAlgorithmException {
		if(javaName == null) {
			if(this.name().equals("ED2K"))
				return new Ed2MessageDigest();
			if(this.name().equals("TTH"))
				return new TigerTree();
		}
		if(name().equals("SHA256")) {
			// User the pool
			return freenet.crypt.SHA256.getMessageDigest();
		} else {
			return MessageDigest.getInstance(javaName, Util.mdProviders.get(javaName));
		}
	}

	public void recycle(MessageDigest md) {
		if(this.equals(SHA256)) {
			freenet.crypt.SHA256.returnMessageDigest(md);
		} // Else no pooling.
	}
}
