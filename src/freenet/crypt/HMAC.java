/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.DigestException;
import java.util.Arrays;

import freenet.support.HexUtil;

/**
 * Implements the HMAC Keyed Message Authentication function, as described
 * in the draft FIPS standard.
 */
public class HMAC {

	// FIXME unsuitable for HmacSHA384 and HmacSHA512

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

	// Restrictions:
	// 1) you can freely use MessageDigest object outside init()/doFinal() pairs
	// 2) you MUST NOT touch digest inside init()/doFinal() pair
	public HMAC(MessageDigest md) {
		this.d = md;
		assert(md.getDigestLength() <= B/2);
	}

	private byte[] K0 = null;
	private boolean reset;

	public void init(byte[] K) {
		init(K, 0, K.length);
	}
	// copies from K, so it is safe to modify afterwards
	public void init(byte[] K, int offset, int length)
		throws ArrayIndexOutOfBoundsException
	{
		d.reset(); // may be unnecessary, but better be safe
		K0 = new byte[B];
		if(length <= B) {
			// Step 1/3
			// original key padded with zeroes to B bytes
			System.arraycopy(K, offset, K0, 0, length);
		} else {
			// Step 2
			d.update(K, offset, length);
			try {
				// digest padded with zeroes to B bytes
				d.digest(K0, 0, d.getDigestLength());
			} catch(DigestException e) {
				// impossible
				throw new Error(e);
			}
		}
		reset();
	}
	public void reset() {
		reset = true;
	}
	final public void update(byte[] data) {
		update(data, 0, data.length);
	}
	public void update(byte[] data, int offset, int length) {
		if (K0 == null) throw new IllegalStateException();

		if (reset) {
			d.reset();

			byte[] IS1 = Util.xor(K0, ipad);
			// Step 5
			d.update(IS1);
			reset = false;
		}

		// Step 6
		d.update(data, offset, length);
	}
	final public byte[] doFinal() {
		byte[] res = new byte[d.getDigestLength()];
		doFinal(res, 0);
		return res;
	}
	// doFinal leaves MessageDigest in reset state
	public void doFinal(byte[] output, int offset) {
		byte[] IS1 = d.digest();
		// Step 7
		byte[] IS2 = Util.xor(K0, opad);

		// Step 8/9
		d.update(IS2);
		d.update(IS1);
		try {
			d.digest(output, offset, IS1.length);
		} catch(DigestException e) {
			// impossible
			throw new Error(e);
		}
		reset = true;
	}

	public int getMacLength() {
		return d.getDigestLength();
	}

	public boolean verify(byte[] K, byte[] text, byte[] mac) {
		byte[] mac2 = mac(K, text, mac.length);
		return Arrays.equals(mac, mac2);
	}

	public byte[] mac(byte[] K, byte[] text, int macbytes) {
		init(K);
		update(text);
		byte[] IS1 = doFinal();
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
		HMAC s = null;
		try {
			s = new HMAC(MessageDigest.getInstance("SHA1"));
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
			HMAC hash = new HMAC(sha256);
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
			HMAC hash = new HMAC(sha256);
			return hash.verify(K, text, mac);
		} finally {
			if(sha256 != null)
				SHA256.returnMessageDigest(sha256);
		}
	}
}	
