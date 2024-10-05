/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;

import org.bitpedia.util.TigerTree;

public enum HashType {
	// warning: keep in sync with Util.mdProviders!
	SHA1(1, "SHA1", 20),
	MD5(2, "MD5", 16),
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

	private final Provider provider;

	HashType(int bitmask, String name, int hashLength) {
		this.bitmask = bitmask;
		this.javaName = name;
		this.hashLength = hashLength;
		this.provider = javaName != null ? Util.mdProviders.get(javaName) : null;
	}

	public final MessageDigest get() {
		if (this == ED2K) {
			return new Ed2MessageDigest();
		}
		if (this == TTH) {
			return new TigerTree();
		}
		try {
			return MessageDigest.getInstance(javaName, provider);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("Unsupported digest algorithm " + javaName, e);
		}
	}

	/**
	 * @deprecated message digests are no longer pooled, there is no need to recycle them
	 */
	@Deprecated
	public final void recycle(MessageDigest md) {
	}
}
