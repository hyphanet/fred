package freenet.crypt.ciphers;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.SecretKeySpec;

import freenet.crypt.BlockCipher;
import freenet.support.Logger;

public class JCACipher implements BlockCipher {
	
	private final int keySize, blockSize;
	private Cipher aesCipher;
	private SecretKeySpec sKey;
	private final String algorithm, cipherTransformation;
	private boolean flag = false;
	
	/**
	 *  Create a JCA based Cipher
	 *  <br /> keySize = 256
	 *  <br /> blockSize = 256
	 *  <br /> Encryption = AES/CFB32
	 */
	public JCACipher(){
		this.keySize = 256;
		this.blockSize = 256;
		this.algorithm = "AES";
		this.cipherTransformation = "AES/CFB32/PKCS5Padding";
	}
	
	/**
	 *  Create a JCA based Cipher
	 *  @param keysize The key size.
	 *  @param blocksize The block size.
	 *  <br /> Encryption = AES/CFB32 
	 */
	public JCACipher(int keySize, int blockSize){
		this.keySize = keySize;
		this.blockSize = blockSize;
		this.algorithm = "AES";
		this.cipherTransformation = "AES/CFB" + (blockSize/8) + "/PKCS5Padding";
	}

	/**
	 *  Create a JCA based Cipher
	 *  @param keysize The key size.
	 *  @param blocksize The block size.
	 *  @param algorithm The name of the SecretKey algorithm. e.g. AES, DES, PBE
	 *  @param cipherTransformation The name of the transformation used to generate Cipher. e.g. AES/CFB32/PKCS5Padding
	 *  <br /> Please refer to JCA Standard Algorithm Name Documentation for details
	 */
	public JCACipher(int keySize, int blockSize, String algorithm, String cipherTransformation){
		this.keySize = keySize;
		this.blockSize = blockSize;
		this.algorithm = algorithm;
		this.cipherTransformation = cipherTransformation;
	}
	
	@Override
	public final void initialize(byte[] key){
		if(!flag){
			try {
				sKey = new SecretKeySpec(key, algorithm);
				aesCipher = Cipher.getInstance(cipherTransformation);
				flag = true;
			} catch (NoSuchAlgorithmException e) {
				e.printStackTrace();
				Logger.error(this,"No such algorithm");
			}catch (NoSuchPaddingException e) {
				e.printStackTrace();
				Logger.error(this,"No such padding mechanism");
			}
		}
		else
			throw new IllegalStateException("Cipher cannot be re-initialized");
	}

	@Override
	public final int getKeySize() {
		return keySize;
	}

	@Override
	public final int getBlockSize() {
		return blockSize;
	}

	@Override
	public synchronized final void encipher(byte[] block, byte[] result) {
		if(flag){
			try {
				aesCipher.init(Cipher.ENCRYPT_MODE, sKey);
				result = aesCipher.doFinal(block);
			} catch (IllegalBlockSizeException e) {
				e.printStackTrace();
				Logger.error(this,"Invalid block size");
			} catch (BadPaddingException e) {
				e.printStackTrace();
				Logger.error(this,"Invalid padding of data");
			} catch (InvalidKeyException e) {
				e.printStackTrace();
				Logger.error(this,"Invalid keys");
			}
		}
		else
			throw new IllegalStateException("Initialize Cipher with a key");
	}

	@Override
	public synchronized final void decipher(byte[] block, byte[] result) {
		if(flag){
			try {
				aesCipher.init(Cipher.DECRYPT_MODE, sKey);
				result = aesCipher.doFinal(block);
			} catch (IllegalBlockSizeException e) {
				e.printStackTrace();
				Logger.error(this,"Invalid block size");
			} catch (BadPaddingException e) {
				e.printStackTrace();
				Logger.error(this,"Invalid padding of data");
			} catch (InvalidKeyException e) {
				e.printStackTrace();
				Logger.error(this,"Invalid keys");
			}
		}
		else
			throw new IllegalStateException("Initialize Cipher with a key");
	}

}
