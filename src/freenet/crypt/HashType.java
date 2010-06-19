/**
 * 
 */
package freenet.crypt;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public enum HashType {
	SHA1(1),
	MD5(2),
	SHA256(4, "SHA-256"),
	SHA384(8, "SHA-384"),
	SHA512(16, "SHA-512");
	
	/** Bitmask for aggregation. */
	public final int bitmask;
	/** Name for MessageDigest purposes. Can contain dashes. */
	public final String javaName;
	
	HashType(int bitmask) {
		this.bitmask = bitmask;
		this.javaName = super.name();
	}
	
	HashType(int bitmask, String name) {
		this.bitmask = bitmask;
		this.javaName = name;
	}
	
	public MessageDigest get() throws NoSuchAlgorithmException {
		if(this.equals(SHA256)) {
			// User the pool
			return freenet.crypt.SHA256.getMessageDigest();
		} else {
			return MessageDigest.getInstance(javaName);
		}
	}
	
	public void recycle(MessageDigest md) {
		if(this.equals(SHA256)) {
			freenet.crypt.SHA256.returnMessageDigest(md);
		} // Else no pooling.
	}
}