package freenet.crypt.ciphers;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import freenet.crypt.BlockCipher;
import freenet.support.Logger;

public class JCACipher implements BlockCipher {
	
	private KeyGenerator keygen;
	private SecretKey aesKey;
	private final int keySize, blockSize;
	private Cipher aesCipher;
	private SecretKeySpec sKey;
	
	public JCACipher(int keySize, int blockSize){
		this.keySize = keySize;
		this.blockSize = blockSize;
	}

	@Override
	public void initialize(byte[] key) {
		try {
			sKey = new SecretKeySpec(key, "AES");
			//keygen = KeyGenerator.getInstance("AES");
			//keygen.init(keySize);
			//aesKey = keygen.generateKey();
			aesCipher = Cipher.getInstance("AES/CFB" + (blockSize/8) + "/PKCS5Padding");
			
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		}catch (NoSuchPaddingException e) {
			e.printStackTrace();
		}
	}

	@Override
	public int getKeySize() {
		return keySize;
	}

	@Override
	public int getBlockSize() {
		return blockSize;
	}

	@Override
	public void encipher(byte[] block, byte[] result) {
		try {
			aesCipher.init(Cipher.ENCRYPT_MODE, sKey);
			result = aesCipher.doFinal(block);
		} catch (IllegalBlockSizeException e) {
			e.printStackTrace();
		} catch (BadPaddingException e) {
			e.printStackTrace();
		} catch (InvalidKeyException e) {
			e.printStackTrace();
		}
	}

	@Override
	public void decipher(byte[] block, byte[] result) {
		try {
			aesCipher.init(Cipher.DECRYPT_MODE, sKey);
			result = aesCipher.doFinal(block);
		} catch (IllegalBlockSizeException e) {
			e.printStackTrace();
		} catch (BadPaddingException e) {
			e.printStackTrace();
		} catch (InvalidKeyException e) {
			e.printStackTrace();
		}
	}

}
