/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt.ciphers;

import java.util.Arrays;
import java.util.Random;

import javax.crypto.Cipher;

import freenet.crypt.UnsupportedCipherException;
import freenet.support.HexUtil;
import junit.framework.TestCase;

/**
 * @author sdiz
 */
public class RijndaelTest extends TestCase {
	private final byte[] PLAINTXT128_1 = HexUtil.hexToBytes("0123456789abcdef1123456789abcdef");
	private final byte[] KEY128_1 = HexUtil.hexToBytes("deadbeefcafebabe0123456789abcdef");
	private final byte[] CIPHER128_1 = HexUtil.hexToBytes("8c5b8c04805c0e07dd62b381730d5d10");

	private final byte[] PLAINTXT192_1 = HexUtil.hexToBytes("0123456789abcdef1123456789abcdef2123456789abcdef");
	private final byte[] KEY192_1 = HexUtil.hexToBytes("deadbeefcafebabe0123456789abcdefcafebabedeadbeef");
	private final byte[] CIPHER192_1 = HexUtil.hexToBytes("7fae974786a9741d96693654bc7a8aff09b3f116840ffced");

	private final byte[] PLAINTXT256_1 = HexUtil
	        .hexToBytes("0123456789abcdef1123456789abcdef2123456789abcdef3123456789abcdef");
	private final byte[] KEY256_1 = HexUtil
	        .hexToBytes("deadbeefcafebabe0123456789abcdefcafebabedeadbeefcafebabe01234567");
	private final byte[] CIPHER256_1 = HexUtil
	        .hexToBytes("6fcbc68fc938e5f5a7c24d7422f4b5f153257b6fb53e0bca26770497dd65078c");

	private static final Random rand = new Random();

	public void testKnownValue() throws UnsupportedCipherException {
		Rijndael aes128 = new Rijndael(128, 128);
		byte[] res128 = new byte[128 / 8];
		aes128.initialize(KEY128_1);
		aes128.encipher(PLAINTXT128_1, res128);
		assertTrue("(128,128) ENCIPHER", Arrays.equals(res128, CIPHER128_1));
		byte[] des128 = new byte[128 / 8];
		aes128.decipher(res128, des128);
		assertTrue("(128,128) DECIPHER", Arrays.equals(des128, PLAINTXT128_1));

		Rijndael aes192 = new Rijndael(192, 192);
		byte[] res192 = new byte[192 / 8];
		aes192.initialize(KEY192_1);
		aes192.encipher(PLAINTXT192_1, res192);
		assertTrue("(192,192) ENCIPHER", Arrays.equals(res192, CIPHER192_1));
		byte[] des192 = new byte[192 / 8];
		aes192.decipher(res192, des192);
		assertTrue("(192,192) DECIPHER", Arrays.equals(des192, PLAINTXT192_1));

		Rijndael aes256 = new Rijndael(256, 256);
		byte[] res256 = new byte[256 / 8];
		aes256.initialize(KEY256_1);
		aes256.encipher(PLAINTXT256_1, res256);
		assertTrue("(256,256) ENCIPHER", Arrays.equals(res256, CIPHER256_1));
		byte[] des256 = new byte[256 / 8];
		aes256.decipher(res256, des256);
		assertTrue("(256,256) DECIPHER", Arrays.equals(des256, PLAINTXT256_1));
	}

	public void testRandom() throws UnsupportedCipherException {
		final int[] SIZE = new int[] { 128, 192, 256 };

		for (int k = 0; k < SIZE.length; k++) {
			int size = SIZE[k];
			Rijndael aes = new Rijndael(size, size);

			byte[] key = new byte[size / 8];
			rand.nextBytes(key);
			aes.initialize(key);

			for (int i = 0; i < 1024; i++) {
				byte[] plain = new byte[size / 8];
				rand.nextBytes(plain);

				byte[] cipher = new byte[size / 8];
				aes.encipher(plain, cipher);

				byte[] plain2 = new byte[size / 8];
				aes.decipher(cipher, plain2);

				assertTrue("(" + size + "," + size + //
				        ") KEY=" + HexUtil.bytesToHex(key) + //
				        ", PLAIN=" + HexUtil.bytesToHex(plain) + //
				        ", CIPHER=" + HexUtil.bytesToHex(cipher) + //
				        ", PLAIN2=" + HexUtil.bytesToHex(plain2),//
				        Arrays.equals(plain, plain2));
			}
		}
	}
}
