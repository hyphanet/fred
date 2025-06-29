/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.security.MessageDigest;

/**
 * Implements the HMAC Keyed Message Authentication function, as described
 * in the draft FIPS standard.
 */
public class HMAC_legacy {

	protected static final int B = 64;
	protected static byte[] ipad = new byte[B];
	protected static byte[] opad = new byte[B];

	static {
		for(int i = 0; i < B; i++) {
			ipad[i] = (byte) 0x36;
			opad[i] = (byte) 0x5c;
		}
	}
	protected MessageDigest d;

	public HMAC_legacy(MessageDigest md) {
		this.d = md;
	}

	public boolean verify(byte[] K, byte[] text, byte[] mac) {
		byte[] mac2 = mac(K, text, mac.length);

		// this is constant-time; DO NOT 'optimize'
		return MessageDigest.isEqual(mac, mac2);
	}

	public byte[] mac(byte[] K, byte[] text, int macbytes) {
		byte[] K0 = null;

		if(K.length == B) // Step 1
			K0 = K;
		else {
			// Step 2
			if(K.length > B)
				K0 = K = Util.hashBytes(d, K);

			if(K.length < B) { // Step 3
				K0 = new byte[B];
				System.arraycopy(K, 0, K0, 0, K.length);
			}
		}

		// Step 4
		byte[] IS1 = Util.xor(K0, ipad);

		// Step 5/6
		d.update(IS1);
		d.update(text);
		IS1 = d.digest();

		// Step 7
		byte[] IS2 = Util.xor(K0, opad);

		// Step 8/9
		d.update(IS2);
		d.update(IS1);
		IS1 = d.digest();

		// Step 10
		if(macbytes == IS1.length)
			return IS1;
		else {
			byte[] rv = new byte[macbytes];
			System.arraycopy(IS1, 0, rv, 0, Math.min(rv.length, IS1.length));
			return rv;
		}
	}

	public static byte[] macWithSHA256(byte[] K, byte[] text, int macbytes) {
		MessageDigest sha256 = SHA256.getMessageDigest();
		HMAC_legacy hash = new HMAC_legacy(sha256);
		return hash.mac(K, text, macbytes);
	}

	public static boolean verifyWithSHA256(byte[] K, byte[] text, byte[] mac) {
		MessageDigest sha256 = SHA256.getMessageDigest();
		HMAC_legacy hash = new HMAC_legacy(sha256);
		return hash.verify(K, text, mac);
	}
}
