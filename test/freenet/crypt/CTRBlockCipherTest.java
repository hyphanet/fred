package freenet.crypt;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import junit.framework.TestCase;

import freenet.crypt.ciphers.Rijndael;
import freenet.support.HexUtil;
import freenet.support.math.MersenneTwister;

public class CTRBlockCipherTest extends TestCase {

	/** Whether to assume JCA is available, and non-crippled. */
	public static final boolean TEST_JCA = Rijndael.AesCtrProvider != null;
	
	static {
		if(!TEST_JCA)
			System.out.println("JCA is crippled, not doing tests requiring JCA");
	}

	private MersenneTwister mt = new MersenneTwister(1634);

	// FIXME test decryptability.

	byte[] NIST_128_ENCRYPT_KEY = HexUtil
			.hexToBytes("2b7e151628aed2a6abf7158809cf4f3c");
	byte[] NIST_128_ENCRYPT_IV = HexUtil
			.hexToBytes("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff");
	byte[] NIST_128_ENCRYPT_PLAINTEXT = HexUtil
			.hexToBytes("6bc1bee22e409f96e93d7e117393172a"
					+ "ae2d8a571e03ac9c9eb76fac45af8e51"
					+ "30c81c46a35ce411e5fbc1191a0a52ef"
					+ "f69f2445df4f9b17ad2b417be66c3710");
	byte[] NIST_128_ENCRYPT_CIPHERTEXT = HexUtil
			.hexToBytes("874d6191b620e3261bef6864990db6ce"
					+ "9806f66b7970fdff8617187bb9fffdff"
					+ "5ae4df3edbd5d35e5b4f09020db03eab"
					+ "1e031dda2fbe03d1792170a0f3009cee");

	byte[] NIST_128_DECRYPT_KEY = HexUtil
			.hexToBytes("2b7e151628aed2a6abf7158809cf4f3c");
	byte[] NIST_128_DECRYPT_IV = HexUtil
			.hexToBytes("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff");
	byte[] NIST_128_DECRYPT_PLAINTEXT = HexUtil
			.hexToBytes("6bc1bee22e409f96e93d7e117393172a"
					+ "ae2d8a571e03ac9c9eb76fac45af8e51"
					+ "30c81c46a35ce411e5fbc1191a0a52ef"
					+ "f69f2445df4f9b17ad2b417be66c3710");
	byte[] NIST_128_DECRYPT_CIPHERTEXT = HexUtil
			.hexToBytes("874d6191b620e3261bef6864990db6ce"
					+ "9806f66b7970fdff8617187bb9fffdff"
					+ "5ae4df3edbd5d35e5b4f09020db03eab"
					+ "1e031dda2fbe03d1792170a0f3009cee");

	byte[] NIST_192_ENCRYPT_KEY = HexUtil
			.hexToBytes("8e73b0f7da0e6452c810f32b809079e562f8ead2522c6b7b");
	byte[] NIST_192_ENCRYPT_IV = HexUtil
			.hexToBytes("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff");
	byte[] NIST_192_ENCRYPT_PLAINTEXT = HexUtil
			.hexToBytes("6bc1bee22e409f96e93d7e117393172a"
					+ "ae2d8a571e03ac9c9eb76fac45af8e51"
					+ "30c81c46a35ce411e5fbc1191a0a52ef"
					+ "f69f2445df4f9b17ad2b417be66c3710");
	byte[] NIST_192_ENCRYPT_CIPHERTEXT = HexUtil
			.hexToBytes("1abc932417521ca24f2b0459fe7e6e0b"
					+ "090339ec0aa6faefd5ccc2c6f4ce8e94"
					+ "1e36b26bd1ebc670d1bd1d665620abf7"
					+ "4f78a7f6d29809585a97daec58c6b050");

	byte[] NIST_192_DECRYPT_KEY = HexUtil
			.hexToBytes("8e73b0f7da0e6452c810f32b809079e562f8ead2522c6b7b");
	byte[] NIST_192_DECRYPT_IV = HexUtil
			.hexToBytes("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff");
	byte[] NIST_192_DECRYPT_PLAINTEXT = HexUtil
			.hexToBytes("6bc1bee22e409f96e93d7e117393172a"
					+ "ae2d8a571e03ac9c9eb76fac45af8e51"
					+ "30c81c46a35ce411e5fbc1191a0a52ef"
					+ "f69f2445df4f9b17ad2b417be66c3710");
	byte[] NIST_192_DECRYPT_CIPHERTEXT = HexUtil
			.hexToBytes("1abc932417521ca24f2b0459fe7e6e0b"
					+ "090339ec0aa6faefd5ccc2c6f4ce8e94"
					+ "1e36b26bd1ebc670d1bd1d665620abf7"
					+ "4f78a7f6d29809585a97daec58c6b050");

	byte[] NIST_256_ENCRYPT_KEY = HexUtil
			.hexToBytes("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4");
	byte[] NIST_256_ENCRYPT_IV = HexUtil
			.hexToBytes("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff");
	byte[] NIST_256_ENCRYPT_PLAINTEXT = HexUtil
			.hexToBytes("6bc1bee22e409f96e93d7e117393172a"
					+ "ae2d8a571e03ac9c9eb76fac45af8e51"
					+ "30c81c46a35ce411e5fbc1191a0a52ef"
					+ "f69f2445df4f9b17ad2b417be66c3710");
	byte[] NIST_256_ENCRYPT_CIPHERTEXT = HexUtil
			.hexToBytes("601ec313775789a5b7a7f504bbf3d228"
					+ "f443e3ca4d62b59aca84e990cacaf5c5"
					+ "2b0930daa23de94ce87017ba2d84988d"
					+ "dfc9c58db67aada613c2dd08457941a6");

	byte[] NIST_256_DECRYPT_KEY = HexUtil
			.hexToBytes("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4");
	byte[] NIST_256_DECRYPT_IV = HexUtil
			.hexToBytes("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff");
	byte[] NIST_256_DECRYPT_PLAINTEXT = HexUtil
			.hexToBytes("6bc1bee22e409f96e93d7e117393172a"
					+ "ae2d8a571e03ac9c9eb76fac45af8e51"
					+ "30c81c46a35ce411e5fbc1191a0a52ef"
					+ "f69f2445df4f9b17ad2b417be66c3710");
	byte[] NIST_256_DECRYPT_CIPHERTEXT = HexUtil
			.hexToBytes("601ec313775789a5b7a7f504bbf3d228"
					+ "f443e3ca4d62b59aca84e990cacaf5c5"
					+ "2b0930daa23de94ce87017ba2d84988d"
					+ "dfc9c58db67aada613c2dd08457941a6");

	public void testNIST() throws UnsupportedCipherException,
			NoSuchAlgorithmException, NoSuchPaddingException,
			InvalidKeyException, InvalidAlgorithmParameterException,
			IllegalBlockSizeException, BadPaddingException {
		// CTR mode test vectors.
		// AES128
		checkNIST(128, NIST_128_ENCRYPT_KEY, NIST_128_ENCRYPT_IV,
				NIST_128_ENCRYPT_PLAINTEXT, NIST_128_ENCRYPT_CIPHERTEXT);
		checkNIST(128, NIST_128_DECRYPT_KEY, NIST_128_DECRYPT_IV,
				NIST_128_DECRYPT_PLAINTEXT, NIST_128_DECRYPT_CIPHERTEXT);
		// AES192
		checkNIST(192, NIST_192_ENCRYPT_KEY, NIST_192_ENCRYPT_IV,
				NIST_192_ENCRYPT_PLAINTEXT, NIST_192_ENCRYPT_CIPHERTEXT);
		checkNIST(192, NIST_192_DECRYPT_KEY, NIST_192_DECRYPT_IV,
				NIST_192_DECRYPT_PLAINTEXT, NIST_192_DECRYPT_CIPHERTEXT);
		// AES256
		checkNIST(256, NIST_256_ENCRYPT_KEY, NIST_256_ENCRYPT_IV,
				NIST_256_ENCRYPT_PLAINTEXT, NIST_256_ENCRYPT_CIPHERTEXT);
		checkNIST(256, NIST_256_DECRYPT_KEY, NIST_256_DECRYPT_IV,
				NIST_256_DECRYPT_PLAINTEXT, NIST_256_DECRYPT_CIPHERTEXT);
	}

	public void testNISTRandomLength() throws UnsupportedCipherException,
			NoSuchAlgorithmException, NoSuchPaddingException,
			InvalidKeyException, InvalidAlgorithmParameterException,
			IllegalBlockSizeException, BadPaddingException,
			ShortBufferException {
		// CTR mode test vectors.
		// AES128
		checkNISTRandomLength(128, NIST_128_ENCRYPT_KEY, NIST_128_ENCRYPT_IV,
				NIST_128_ENCRYPT_PLAINTEXT, NIST_128_ENCRYPT_CIPHERTEXT);
		checkNISTRandomLength(128, NIST_128_DECRYPT_KEY, NIST_128_DECRYPT_IV,
				NIST_128_DECRYPT_PLAINTEXT, NIST_128_DECRYPT_CIPHERTEXT);
		// AES192
		checkNISTRandomLength(192, NIST_192_ENCRYPT_KEY, NIST_192_ENCRYPT_IV,
				NIST_192_ENCRYPT_PLAINTEXT, NIST_192_ENCRYPT_CIPHERTEXT);
		checkNISTRandomLength(192, NIST_192_DECRYPT_KEY, NIST_192_DECRYPT_IV,
				NIST_192_DECRYPT_PLAINTEXT, NIST_192_DECRYPT_CIPHERTEXT);
		// AES256
		checkNISTRandomLength(256, NIST_256_ENCRYPT_KEY, NIST_256_ENCRYPT_IV,
				NIST_256_ENCRYPT_PLAINTEXT, NIST_256_ENCRYPT_CIPHERTEXT);
		checkNISTRandomLength(256, NIST_256_DECRYPT_KEY, NIST_256_DECRYPT_IV,
				NIST_256_DECRYPT_PLAINTEXT, NIST_256_DECRYPT_CIPHERTEXT);
	}

	private void checkNIST(int bits, byte[] key, byte[] iv, byte[] plaintext,
			byte[] ciphertext) throws UnsupportedCipherException,
			NoSuchAlgorithmException, NoSuchPaddingException,
			InvalidKeyException, InvalidAlgorithmParameterException,
			IllegalBlockSizeException, BadPaddingException {
		// First test it with JCA.
		if (TEST_JCA) {
			SecretKeySpec k = new SecretKeySpec(key, "AES");
			Cipher c = Cipher.getInstance("AES/CTR/NOPADDING", Rijndael.AesCtrProvider);
			c.init(Cipher.ENCRYPT_MODE, k, new IvParameterSpec(iv));
			byte[] output = c.doFinal(plaintext);
			assertTrue(Arrays.equals(output, ciphertext));
		}

		Rijndael cipher = new Rijndael(bits, 128);
		cipher.initialize(key);
		CTRBlockCipher ctr = new CTRBlockCipher(cipher);
		ctr.init(iv);
		byte[] output = new byte[plaintext.length];
		ctr.processBytes(plaintext, 0, plaintext.length, output, 0);
		assertTrue(Arrays.equals(output, ciphertext));
	}

	private void checkNISTRandomLength(int bits, byte[] key, byte[] iv,
			byte[] plaintext, byte[] ciphertext)
			throws UnsupportedCipherException, NoSuchAlgorithmException,
			NoSuchPaddingException, InvalidKeyException,
			InvalidAlgorithmParameterException, IllegalBlockSizeException,
			BadPaddingException, ShortBufferException {
		for (int i = 0; i < 1024; i++) {
			// First test it with JCA.
			long seed = mt.nextLong();
			if (TEST_JCA) {
				SecretKeySpec k = new SecretKeySpec(key, "AES");
				Cipher c = Cipher.getInstance("AES/CTR/NOPADDING", Rijndael.AesCtrProvider);
				c.init(Cipher.ENCRYPT_MODE, k, new IvParameterSpec(iv));
				MersenneTwister random = new MersenneTwister(seed);
				byte[] output = new byte[plaintext.length];
				int inputPtr = 0;
				int outputPtr = 0;
				// Odd API designed for block ciphers etc.
				// For CTR it should be able to return immediately each time.
				// ... Actually, no. BouncyCastle's CTR breaks this assumption.
				// You must handle when update() produce less than was in input.
				while (inputPtr < plaintext.length) {
					int max = plaintext.length - inputPtr;
					int count = (max == 1) ? 1 : (random.nextInt(max - 1) + 1);
					int moved = c.update(plaintext, inputPtr, count, output,
							outputPtr);
					outputPtr += moved;
					inputPtr += count;
				}
				c.doFinal(plaintext, 0, plaintext.length - inputPtr, output,
						outputPtr);
				assertTrue(Arrays.equals(output, ciphertext));
			}

			Rijndael cipher = new Rijndael(bits, 128);
			cipher.initialize(key);
			CTRBlockCipher ctr = new CTRBlockCipher(cipher);
			ctr.init(iv);
			byte[] output = new byte[plaintext.length];
			MersenneTwister random = new MersenneTwister(seed);
			int ptr = 0;
			while (ptr < plaintext.length) {
				int max = plaintext.length - ptr;
				int count = (max == 1) ? 1 : (random.nextInt(max - 1) + 1);
				ctr.processBytes(plaintext, ptr, count, output, ptr);
				ptr += count;
			}
			assertTrue(Arrays.equals(output, ciphertext));
		}
	}
	
	public void testRandomJCA() throws NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException {
		if(!TEST_JCA) return;
		for(int i=0;i<1024;i++) {
			byte[] plaintext = new byte[mt.nextInt(4096)+1];
			byte[] key = new byte[32];
			byte[] iv = new byte[16];
			mt.nextBytes(plaintext);
			mt.nextBytes(key);
			mt.nextBytes(iv);
			SecretKeySpec k = new SecretKeySpec(key, "AES");
			Cipher c = Cipher.getInstance("AES/CTR/NOPADDING", Rijndael.AesCtrProvider);
			c.init(Cipher.ENCRYPT_MODE, k, new IvParameterSpec(iv));
			byte[] output = c.doFinal(plaintext);
			c = Cipher.getInstance("AES/CTR/NOPADDING", Rijndael.AesCtrProvider);
			c.init(Cipher.DECRYPT_MODE, k, new IvParameterSpec(iv));
			byte[] decrypted = c.doFinal(output);
			assertTrue(Arrays.equals(decrypted, plaintext));
		}
	}
	
	public void testRandom() throws UnsupportedCipherException, NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException {
		for(int i=0;i<1024;i++) {
			byte[] plaintext = new byte[mt.nextInt(4096)+1];
			byte[] key = new byte[32];
			byte[] iv = new byte[16];
			mt.nextBytes(plaintext);
			mt.nextBytes(key);
			mt.nextBytes(iv);
			// First encrypt as a block.
			Rijndael cipher = new Rijndael(256, 128);
			cipher.initialize(key);
			CTRBlockCipher ctr = new CTRBlockCipher(cipher);
			ctr.init(iv);
			byte[] ciphertext = new byte[plaintext.length];
			ctr.processBytes(plaintext, 0, plaintext.length, ciphertext, 0);
			// Now decrypt.
			ctr = new CTRBlockCipher(cipher);
			ctr.init(iv);
			byte[] finalPlaintext = new byte[plaintext.length];
			ctr.processBytes(ciphertext, 0, ciphertext.length, finalPlaintext, 0);
			assertTrue(Arrays.equals(finalPlaintext, plaintext));
			if(TEST_JCA) {
				SecretKeySpec k = new SecretKeySpec(key, "AES");
				Cipher c = Cipher.getInstance("AES/CTR/NOPADDING", Rijndael.AesCtrProvider);
				c.init(Cipher.ENCRYPT_MODE, k, new IvParameterSpec(iv));
				byte[] output = c.doFinal(plaintext);
				assertTrue(Arrays.equals(output, ciphertext));
				c = Cipher.getInstance("AES/CTR/NOPADDING", Rijndael.AesCtrProvider);
				c.init(Cipher.DECRYPT_MODE, k, new IvParameterSpec(iv));
				byte[] decrypted = c.doFinal(output);
				assertTrue(Arrays.equals(decrypted, plaintext));
			}
			// Now encrypt again, in random pieces.
			cipher.initialize(key);
			ctr = new CTRBlockCipher(cipher);
			ctr.init(iv);
			byte[] output = new byte[plaintext.length];
			MersenneTwister random = new MersenneTwister(mt.nextLong());
			int ptr = 0;
			while (ptr < plaintext.length) {
				int max = plaintext.length - ptr;
				int count = (max == 1) ? 1 : (random.nextInt(max - 1) + 1);
				ctr.processBytes(plaintext, ptr, count, output, ptr);
				ptr += count;
			}
			assertTrue(Arrays.equals(output, ciphertext));
			
		}
	}

}
