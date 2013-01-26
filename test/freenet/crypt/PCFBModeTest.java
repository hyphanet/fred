package freenet.crypt;

import java.util.Arrays;

import junit.framework.TestCase;

import freenet.crypt.ciphers.Rijndael;
import freenet.support.HexUtil;
import freenet.support.math.MersenneTwister;

// 256,256 PCFB is the same as 256,256 CFB, however JCA does not support 256-bit block size, so we can't
// test against JCA. We will move to the standard block size, and stop using PCFB, eventually, but we'll 
// need PCFB for a while if only for old keys, so we need to test it.
public class PCFBModeTest extends TestCase {

	private MersenneTwister mt = new MersenneTwister(1634);

	// FIXME I don't think there are any standard test vectors?
	byte[] PCFB_256_ENCRYPT_KEY = HexUtil
			.hexToBytes("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4");
	// FIXME This IV was tailored for CTR mode and 128-bit block, maybe needs adjustement
	byte[] PCFB_256_ENCRYPT_IV = HexUtil
			.hexToBytes("f0f1f2f3f4f5f6f7f8f9fafbfcfdfefff0f1f2f3f4f5f6f7f8f9fafbfcfdfeff");
	// FIXME This plaintext was tailored for 128-bit block, maybe needs adjustement
	byte[] PCFB_256_ENCRYPT_PLAINTEXT = HexUtil
			.hexToBytes("6bc1bee22e409f96e93d7e117393172a"
					+ "ae2d8a571e03ac9c9eb76fac45af8e51"
					+ "30c81c46a35ce411e5fbc1191a0a52ef"
					+ "f69f2445df4f9b17ad2b417be66c3710");
	byte[] PCFB_256_ENCRYPT_CIPHERTEXT = HexUtil
			.hexToBytes("c964b00326e216214f1a68f5b0872608"
					+ "1b403c92fe02898664a81f5bbbbf8341"
					+ "fc1d04b2c1addfb826cca1eab6813127"
					+ "2751b9d6cd536f78059b10b4867dbbd9");

	byte[] PCFB_256_DECRYPT_KEY = PCFB_256_ENCRYPT_KEY;
	byte[] PCFB_256_DECRYPT_IV = PCFB_256_ENCRYPT_IV;
	byte[] PCFB_256_DECRYPT_PLAINTEXT = PCFB_256_ENCRYPT_PLAINTEXT;
	byte[] PCFB_256_DECRYPT_CIPHERTEXT = PCFB_256_ENCRYPT_CIPHERTEXT;

	public void testKnownValues() throws UnsupportedCipherException {
		// Rijndael(256,256)
		checkKnownValues(256, PCFB_256_ENCRYPT_KEY, PCFB_256_ENCRYPT_IV,
				PCFB_256_ENCRYPT_PLAINTEXT, PCFB_256_ENCRYPT_CIPHERTEXT);
		checkKnownValues(256, PCFB_256_DECRYPT_KEY, PCFB_256_DECRYPT_IV,
				PCFB_256_DECRYPT_PLAINTEXT, PCFB_256_DECRYPT_CIPHERTEXT);
	}

	public void testKnownValuesRandomLength() throws UnsupportedCipherException {
		// Rijndael(256,256)
		checkKnownValuesRandomLength(256, PCFB_256_ENCRYPT_KEY, PCFB_256_ENCRYPT_IV,
				PCFB_256_ENCRYPT_PLAINTEXT, PCFB_256_ENCRYPT_CIPHERTEXT);
		checkKnownValuesRandomLength(256, PCFB_256_DECRYPT_KEY, PCFB_256_DECRYPT_IV,
				PCFB_256_DECRYPT_PLAINTEXT, PCFB_256_DECRYPT_CIPHERTEXT);
	}

	private void checkKnownValues(int bits, byte[] key, byte[] iv, byte[] plaintext,
			byte[] ciphertext) throws UnsupportedCipherException {
		Rijndael cipher = new Rijndael(bits, bits);
		cipher.initialize(key);
		PCFBMode ctr = PCFBMode.create(cipher);
		ctr.reset(iv);
		byte[] output = new byte[plaintext.length];
		System.arraycopy(plaintext, 0, output, 0, plaintext.length);
		//ctr.blockEncipher(plaintext, 0, plaintext.length, output, 0);
		ctr.blockEncipher(output, 0, output.length);
		//System.out.println(HexUtil.bytesToHex(output));
		assertTrue(Arrays.equals(output, ciphertext));
		ctr.reset(iv);
		//ctr.blockDecipher(output, 0, output.length, output, 0);
		ctr.blockDecipher(output, 0, output.length);
		assertTrue(Arrays.equals(output, plaintext));
	}
	private void checkKnownValuesRandomLength(int bits, byte[] key, byte[] iv,
			byte[] plaintext, byte[] ciphertext)
			throws UnsupportedCipherException {
		for (int i = 0; i < 1024; i++) {
			long seed = mt.nextLong();

			Rijndael cipher = new Rijndael(bits, bits);
			cipher.initialize(key);
			PCFBMode ctr = PCFBMode.create(cipher);
			ctr.reset(iv);
			byte[] output = new byte[plaintext.length];
			MersenneTwister random = new MersenneTwister(seed);
			int ptr = 0;
			System.arraycopy(plaintext, 0, output, 0, plaintext.length);
			while (ptr < plaintext.length) {
				int max = plaintext.length - ptr;
				int count = (max == 1) ? 1 : (random.nextInt(max - 1) + 1);
				/*ctr.blockEncipher(plaintext, ptr, count, output, ptr);*/
				ctr.blockEncipher(output, ptr, count);
				ptr += count;
			}
			assertTrue(Arrays.equals(output, ciphertext));
			ctr.reset(iv);
			ptr = 0;
			while (ptr < plaintext.length) {
				int max = plaintext.length - ptr;
				int count = (max == 1) ? 1 : (random.nextInt(max - 1) + 1);
				/*ctr.blockDecipher(output, ptr, count, output, ptr);*/
				ctr.blockDecipher(output, ptr, count);
				ptr += count;
			}
			assertTrue(Arrays.equals(output, plaintext));
		}
	}
	
	public void testRandom() throws UnsupportedCipherException {
		for(int i=0;i<1024;i++) {
			byte[] plaintext = new byte[mt.nextInt(4096)+1];
			byte[] key = new byte[32];
			byte[] iv = new byte[32];
			mt.nextBytes(plaintext);
			mt.nextBytes(key);
			mt.nextBytes(iv);
			// First encrypt as a block.
			Rijndael cipher = new Rijndael(256, 256);
			cipher.initialize(key);
			PCFBMode ctr = PCFBMode.create(cipher);
			ctr.reset(iv);
			byte[] ciphertext = new byte[plaintext.length];
			System.arraycopy(plaintext, 0, ciphertext, 0, ciphertext.length);
			//ctr.blockEncipher(plaintext, 0, plaintext.length, ciphertext, 0);
			ctr.blockEncipher(ciphertext, 0, ciphertext.length);
			// Now decrypt.
			ctr = PCFBMode.create(cipher);
			ctr.reset(iv);
			byte[] finalPlaintext = new byte[plaintext.length];
			System.arraycopy(ciphertext, 0, finalPlaintext, 0, ciphertext.length);
			//ctr.blockDecipher(ciphertext, 0, ciphertext.length, finalPlaintext, 0);
			ctr.blockDecipher(finalPlaintext, 0, finalPlaintext.length);
			assertTrue(Arrays.equals(finalPlaintext, plaintext));

			// Now encrypt again, in random pieces.
			cipher.initialize(key);
			ctr = PCFBMode.create(cipher);
			ctr.reset(iv);
			byte[] output = new byte[plaintext.length];

			MersenneTwister random = new MersenneTwister(mt.nextLong());
			int ptr = 0;
			System.arraycopy(plaintext, 0, output, 0, plaintext.length);
			while (ptr < plaintext.length) {
				int max = plaintext.length - ptr;
				int count = (max == 1) ? 1 : (random.nextInt(max - 1) + 1);
				//ctr.blockEncipher(plaintext, ptr, count, output, ptr);
				ctr.blockEncipher(output, ptr, count);
				ptr += count;
			}
			assertTrue(Arrays.equals(output, ciphertext));
			// ... and decrypt again, in random pieces.
			ptr = 0;
			ctr.reset(iv);
			while (ptr < plaintext.length) {
				int max = plaintext.length - ptr;
				int count = (max == 1) ? 1 : (random.nextInt(max - 1) + 1);
				//ctr.blockDecipher(output, ptr, count, output, ptr);
				ctr.blockDecipher(output, ptr, count);
				ptr += count;
			}
			assertTrue(Arrays.equals(output, plaintext));
			
		}
	}

}
