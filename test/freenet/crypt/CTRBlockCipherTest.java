package freenet.crypt;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import junit.framework.TestCase;

import freenet.crypt.ciphers.Rijndael;
import freenet.support.HexUtil;

public class CTRBlockCipherTest extends TestCase {

	/** Whether to assume JCA is available, and non-crippled. */
	public static final boolean TEST_JCA = true;
	
	// FIXME test decryptability.

	// FIXME test against JCE if available.

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

	public void testNIST() throws UnsupportedCipherException, NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException {
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

	private void checkNIST(int bits, byte[] key, byte[] iv, byte[] plaintext,
			byte[] ciphertext) throws UnsupportedCipherException, NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException {
		// First test it with JCA.
		if(TEST_JCA) {
			SecretKeySpec k = 
				new SecretKeySpec(key, "AES");
			Cipher c = Cipher.getInstance("AES/CTR/NOPADDING");
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

}
