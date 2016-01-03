/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import freenet.support.HexUtil;

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

	public static void main(String[] args) throws UnsupportedEncodingException {
		HMAC_legacy s = null;
		try {
			s = new HMAC_legacy(MessageDigest.getInstance("SHA1"));
		} catch(NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
		byte[] key = new byte[20];
		System.err.println("20x0b, 'Hi There':");
		byte[] text;
		text = "Hi There".getBytes("UTF-8");

		for(int i = 0; i < key.length; i++)
			key[i] = (byte) 0x0b;

		byte[] mv = s.mac(key, text, 20);
		System.out.println(HexUtil.bytesToHex(mv, 0, mv.length));

		System.err.println("20xaa, 50xdd:");
		for(int i = 0; i < key.length; i++)
			key[i] = (byte) 0xaa;
		text = new byte[50];
		for(int i = 0; i < text.length; i++)
			text[i] = (byte) 0xdd;
		mv = s.mac(key, text, 20);
		System.out.println(HexUtil.bytesToHex(mv, 0, mv.length));

		key = new byte[25];
		System.err.println("25x[i+1], 50xcd:");
		for(int i = 0; i < key.length; i++)
			key[i] = (byte) (i + 1);
		for(int i = 0; i < text.length; i++)
			text[i] = (byte) 0xcd;
		mv = s.mac(key, text, 20);
		System.out.println(HexUtil.bytesToHex(mv, 0, mv.length));

		key = new byte[20];
		System.err.println("20x0c, 'Test With Truncation':");
		for(int i = 0; i < key.length; i++)
			key[i] = (byte) 0x0c;
		text = "Test With Truncation".getBytes("UTF-8");
		mv = s.mac(key, text, 20);
		System.out.println(HexUtil.bytesToHex(mv, 0, mv.length));
		mv = s.mac(key, text, 12);
		System.out.println(HexUtil.bytesToHex(mv, 0, mv.length));

	}

	public static byte[] macWithSHA256(byte[] K, byte[] text, int macbytes) {
		MessageDigest sha256 = null;
		try {
			sha256 = SHA256.getMessageDigest();
			HMAC_legacy hash = new HMAC_legacy(sha256);
			return hash.mac(K, text, macbytes);
		} finally {
			if(sha256 != null)
				SHA256.returnMessageDigest(sha256);
		}
	}

	public static boolean verifyWithSHA256(byte[] K, byte[] text, byte[] mac) {
		MessageDigest sha256 = null;
		try {
			sha256 = SHA256.getMessageDigest();
			HMAC_legacy hash = new HMAC_legacy(sha256);
			return hash.verify(K, text, mac);
		} finally {
			if(sha256 != null)
				SHA256.returnMessageDigest(sha256);
		}
	}
}
