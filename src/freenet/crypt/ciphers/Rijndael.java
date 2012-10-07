package freenet.crypt.ciphers;

import java.security.InvalidKeyException;
import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import freenet.crypt.BlockCipher;
import freenet.crypt.UnsupportedCipherException;
import freenet.support.Logger;

/*
  This code is part of the Java Adaptive Network Client by Ian Clarke. 
  It is distributed under the GNU Public Licence (GPL) version 2.  See
  http://www.gnu.org/ for further details of the GPL.
 */

/**
 * Interfaces with the Rijndael AES candidate to implement the Rijndael
 * algorithm
 */
public class Rijndael implements BlockCipher {
	private Object sessionKey;
	private final int keysize, blocksize;

	public static final boolean isJCACrippled = isJCACrippled();
	private static String provider;
	
	public static String getProviderName() {
		return provider;
	}
	
	/** @return True if JCA is crippled (restricted to 128-bit) so we need 
	 * to use this class. */
	private static boolean isJCACrippled() {
		try {
			byte[] key = new byte[32]; // Test for whether 256-bit works.
			byte[] iv = new byte[16];
			byte[] plaintext = new byte[16];
			SecretKeySpec k = new SecretKeySpec(key, "AES");
			Cipher c = Cipher.getInstance("AES/CTR/NOPADDING");
			c.init(Cipher.ENCRYPT_MODE, k, new IvParameterSpec(iv));
			c.doFinal(plaintext);
			provider = c.getProvider().getName();
			Logger.normal(Rijndael.class, "Using JCA: provider "+provider);
			return false;
		} catch (GeneralSecurityException e) {
			Logger.warning(Rijndael.class, "Not using JCA as it is crippled (can't use 256-bit keys). Will use built-in encryption. ", e);
			return true;
		}
	}
	
	/**
	 * Create a Rijndael instance.
	 * @param keysize The key size.
	 * @param blocksize The block size.
	 * @throws UnsupportedCipherException
	 */
	public Rijndael(int keysize, int blocksize) throws UnsupportedCipherException {
		if (! ((keysize == 128) ||
				(keysize == 192) ||
				(keysize == 256)))
			throw new UnsupportedCipherException("Invalid keysize");
		if (! ((blocksize == 128) ||
				(blocksize == 192) ||
				(blocksize == 256)))
			throw new UnsupportedCipherException("Invalid blocksize");
		this.keysize=keysize;
		this.blocksize=blocksize;
	}

	// for Util.getCipherByName..  and yes, screw you too, java
	public Rijndael() {
		this.keysize   = 128;
		this.blocksize = 128;
	}

	@Override
	public final int getBlockSize() {
		return blocksize;
	}

	@Override
	public final int getKeySize() {
		return keysize;
	}

	@Override
	public final void initialize(byte[] key) {
		try {
			byte[] nkey=new byte[keysize>>3];
			System.arraycopy(key, 0, nkey, 0, nkey.length);
			sessionKey=Rijndael_Algorithm.makeKey(nkey, blocksize/8);
		} catch (InvalidKeyException e) {
			e.printStackTrace();
			Logger.error(this,"Invalid key");
		}
	}

	@Override
	public synchronized final void encipher(byte[] block, byte[] result) {
		if(block.length != blocksize/8)
			throw new IllegalArgumentException();
		Rijndael_Algorithm.blockEncrypt(block, result, 0, sessionKey, blocksize/8);
	}

	/**
	 * @return Size of temporary int[] a, t. If these are passed in, this can speed
	 * things up by avoiding unnecessary allocations between rounds.
	 */
	// only consumer is RijndaelPCFBMode
	public synchronized final int getTempArraySize() {
		return blocksize/(8*4);
	}

	// only consumer is RijndaelPCFBMode
	public synchronized final void encipher(byte[] block, byte[] result, int[] a, int[] t) {
		if(block.length != blocksize/8)
			throw new IllegalArgumentException();
		if(a.length != t.length || t.length != blocksize/(8*4))
			throw new IllegalArgumentException();
		Rijndael_Algorithm.blockEncrypt(block, result, 0, sessionKey, blocksize/8, a, t);
	}

	@Override
	public synchronized final void decipher(byte[] block, byte[] result) {
		if(block.length != blocksize/8)
			throw new IllegalArgumentException();
		Rijndael_Algorithm.blockDecrypt(block, result, 0, sessionKey, blocksize/8);
	}
}
