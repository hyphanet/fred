/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import junit.framework.TestCase;
import junit.framework.ComparisonFailure;

public class HMACTest extends TestCase {
	static SecureRandom r = new SecureRandom();

	// "replacement" for junit4 assertArrayEquals
	private static String toHex(byte[] arg) {
		return String.format("%0"+(arg.length*2)+"x", new BigInteger(1,arg));
	}

	// strangely missing from junit
	static public void assertNotEquals(String expected, String actual) {
		assertNotEquals(null, expected, actual);
	}
	static public void assertNotEquals(String message, String expected, String actual) {
		if (expected == null && actual != null)
			return;
		if (expected != null && !expected.equals(actual))
			return;
		throw new ComparisonFailure(message, expected, actual);
	}

	// FIXME also check test vectors from rfc4231

	public void testKeyPadding()
		throws InvalidKeyException, NoSuchAlgorithmException
	{
		byte[] data = new byte[123];
		r.nextBytes(data);
		MessageDigest md = SHA256.getMessageDigest();
		byte [] emptyMD = md.digest();
		HMAC HMAChmac = new HMAC(md);
		Mac JCAmac = Mac.getInstance("HmacSHA256");
		// test all key length
		for(int keyLen = 1; keyLen < HMAC.B*2; keyLen++) {
			byte[] rawkey = new byte[keyLen];
			r.nextBytes(rawkey);

			HMAChmac.init(rawkey);
			HMAChmac.update(data);
			byte[] HMACres = HMAChmac.doFinal();
			JCAmac.init(new SecretKeySpec(rawkey, "HmacSHA256"));
			JCAmac.update(data);
			byte[] JCAres = JCAmac.doFinal();
			// check result matches JCA
			assertEquals("freenet.crypt.HMAC matches javax.crypto.Mac", toHex(HMACres), toHex(JCAres));
			assertEquals("md is reset after HMAC.doFinal()", toHex(md.digest()), toHex(emptyMD));

			// test result different with different data
			data[0] ^= (byte)0xff;
			HMAChmac.update(data);
			byte[] HMACres2 = HMAChmac.doFinal();
			assertNotEquals("HMAC result different with different data [actual must NOT match expected]", toHex(HMACres), toHex(HMACres2));
			assertEquals("md is reset after HMAC.doFinal()", toHex(md.digest()), toHex(emptyMD));

			// test reset works
			HMAChmac.update(data);
			HMAChmac.reset();
			data[0] ^= (byte)0xff;
			HMAChmac.update(data);
			byte[] HMACres3 = HMAChmac.doFinal();
			assertEquals("HMAC reset works", toHex(HMACres), toHex(HMACres3));
			assertEquals("md is reset after HMAC.doFinal()", toHex(md.digest()), toHex(emptyMD));

			// test interaction with meddling with md
			HMAChmac.update(data, 0, 0);
			md.update(data);
			HMAChmac.update(data);
			byte[] HMACres4 = HMAChmac.doFinal();
			assertNotEquals("dirty MD corrupts HMAC [actual must NOT match expected]", toHex(HMACres), toHex(HMACres4));

			HMAChmac.update(data, 0, 0);
			md.update(data);
			HMAChmac.reset();
			HMAChmac.update(data);
			byte[] HMACres5 = HMAChmac.doFinal();
			assertEquals("HMAC reset works", toHex(HMACres), toHex(HMACres5));
			assertEquals("md is reset after HMAC.doFinal()", toHex(md.digest()), toHex(emptyMD));
		}
		SHA256.returnMessageDigest(md);
	}
}
