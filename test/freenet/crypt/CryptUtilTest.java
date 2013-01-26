/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.util.Arrays;
import java.security.SecureRandom;
import java.security.NoSuchAlgorithmException;

import junit.framework.TestCase;

import freenet.support.math.MersenneTwister;
import freenet.support.Fields;

public class CryptUtilTest extends TestCase {

	public void testRandomBytes()
	{
		// two predictable pseudo-random sequence
		MersenneTwister mt1 = new MersenneTwister(Long.MAX_VALUE);
		MersenneTwister mt2 = new MersenneTwister(Long.MAX_VALUE);

		for(int off = 0; off < 15; off++) {
			for(int len = 0; len < 31; len++) {
				byte[] b1 = new byte[len];
				byte[] b2 = new byte[len + off];
				mt1.nextBytes(b1);
				Util.randomBytes(mt2, b2, off, len);
				assertTrue("Random offset="+off+" length="+len,
						Fields.byteArrayEqual(b1, b2, 0, off, len));
			}
		}
	}
	public void testSecureRandomBytes()
	{
		SecureRandom r1;
		SecureRandom r2;
		try {
			r1 = SecureRandom.getInstance("SHA1PRNG");
			r2 = SecureRandom.getInstance("SHA1PRNG");
		} catch(NoSuchAlgorithmException e) {
			System.err.println("Cannot acquire SHA1PRNG, skipping test: "+e);
			e.printStackTrace();
			return;
		}
		// SHA1PRNG have repeatable output when seeded
		try {
			byte[] seed = "foobar barfoo feedbeef barfeed".getBytes("UTF-8");
			r1.setSeed(seed);
			r2.setSeed(seed);
		} catch(Throwable e) {
			throw new Error("Cannot seed SHA1PRNG", e);
		}
		// Confirm
		{
			byte[] b1 = new byte[128];
			byte[] b2 = new byte[128];
			r1.nextBytes(b1);
			r2.nextBytes(b2);
			if (!Arrays.equals(b1, b2)) {
				System.err.println("SHA1PRNG is not repeatable despite same seed, skipping test");
				return;
			}
		}
		for(int off = 0; off < 15; off++) {
			for(int len = 0; len < 31; len++) {
				byte[] b1 = new byte[len];
				byte[] b2 = new byte[len + off];
				r1.nextBytes(b1);
				Util.randomBytes(r2, b2, off, len);
				assertTrue("SecureRandom offset="+off+" length="+len,
						Fields.byteArrayEqual(b1, b2, 0, off, len));
			}
		}
	}
}
