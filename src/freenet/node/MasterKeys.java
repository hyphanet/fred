package freenet.node;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;

import freenet.crypt.BlockCipher;
import freenet.crypt.PCFBMode;
import freenet.crypt.RandomSource;
import freenet.crypt.SHA256;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.ciphers.Rijndael;
import freenet.support.Fields;
import freenet.support.HexUtil;

/** Keys read from the master keys file */
public class MasterKeys {
	
	// Currently we only encrypt the client cache
	
	final byte[] clientCacheMasterKey;
	
	public MasterKeys(byte[] clientCacheKey) {
		this.clientCacheMasterKey = clientCacheKey;
	}

	void clearClientCacheKeys() {
		clear(clientCacheMasterKey);
	}

	static final int HASH_LENGTH = 4;
	
	public static MasterKeys read(File masterKeysFile, RandomSource hardRandom, String password) throws MasterKeysWrongPasswordException, MasterKeysFileTooBigException, MasterKeysFileTooShortException, IOException {
		System.err.println("Trying to read master keys file...");
		if(masterKeysFile != null) {
			// Try to read the keys
			FileInputStream fis;
			// FIXME move declarations of sensitive data out and clear() in finally {}
			try {
				fis = new FileInputStream(masterKeysFile);
				DataInputStream dis = new DataInputStream(fis);
				byte[] salt = new byte[32];
				dis.readFully(salt);
				byte[] iv = new byte[32];
				dis.readFully(iv);
				int length = (int)Math.min(Integer.MAX_VALUE, masterKeysFile.length());
				if(masterKeysFile.length() > 1024) throw new MasterKeysFileTooBigException();
				if(masterKeysFile.length() < (32 + 32 + 8 + 32)) throw new MasterKeysFileTooShortException();
				byte[] dataAndHash = new byte[length - salt.length - iv.length];
				dis.readFully(dataAndHash);
//				System.err.println("Data and hash: "+HexUtil.bytesToHex(dataAndHash));
				byte[] pwd = password.getBytes("UTF-8");
				MessageDigest md = SHA256.getMessageDigest();
				md.update(pwd);
				md.update(salt);
				byte[] outerKey = md.digest();
				BlockCipher cipher;
				try {
					cipher = new Rijndael(256, 256);
				} catch (UnsupportedCipherException e) {
					// Impossible
					throw new Error(e);
				}
//				System.err.println("Outer key: "+HexUtil.bytesToHex(outerKey));
				cipher.initialize(outerKey);
				PCFBMode pcfb = PCFBMode.create(cipher, iv);
				pcfb.blockDecipher(dataAndHash, 0, dataAndHash.length);
//				System.err.println("Decrypted data and hash: "+HexUtil.bytesToHex(dataAndHash));
				byte[] data = new byte[dataAndHash.length - HASH_LENGTH];
				byte[] hash = new byte[HASH_LENGTH];
				System.arraycopy(dataAndHash, 0, data, 0, dataAndHash.length - HASH_LENGTH);
//				System.err.println("Data: "+HexUtil.bytesToHex(data));
				System.arraycopy(dataAndHash, dataAndHash.length - HASH_LENGTH, hash, 0, HASH_LENGTH);
//				System.err.println("Hash: "+HexUtil.bytesToHex(hash));
				clear(dataAndHash);
				byte[] checkHash = md.digest(data);
//				System.err.println("Check hash: "+HexUtil.bytesToHex(checkHash));
				if(!arraysEqualTruncated(checkHash, hash, HASH_LENGTH)) {
					clear(data);
					clear(hash);
					throw new MasterKeysWrongPasswordException();
				}
				
				// It matches. Now decode it.
				ByteArrayInputStream bais = new ByteArrayInputStream(data);
				dis = new DataInputStream(bais);
				// FIXME Fields.longToBytes and dis.readLong may not be compatible, find out if they are.
				byte[] flagsBytes = new byte[8];
				dis.readFully(flagsBytes);
				long flags = Fields.bytesToLong(flagsBytes);
				// At the moment there are no interesting flags.
				// In future the flags will tell us whether the database and the datastore are encrypted.
				byte[] clientCacheKey = new byte[32];
				dis.readFully(clientCacheKey);
				MasterKeys ret = new MasterKeys(clientCacheKey);
				clear(data);
				clear(hash);
				SHA256.returnMessageDigest(md);
				System.err.println("Read old master keys file");
				return ret;
			} catch (FileNotFoundException e) {
				// Ok, create a new one.
			} catch (UnsupportedEncodingException e) {
				// Impossible
				System.err.println("JVM doesn't support UTF-8, this should be impossible!");
				throw new Error(e);
			} catch (EOFException e) {
				throw new MasterKeysFileTooShortException();
			}
		}
		System.err.println("Creating new master keys file");
		// If we are still here, we need to create a new master keys file
		// FIXME try{}, move declarations of sensitive data out, clear() in finally {}
		byte[] clientCacheKey = new byte[32];
		hardRandom.nextBytes(clientCacheKey);
		byte[] iv = new byte[32];
		hardRandom.nextBytes(iv);
		byte[] salt = new byte[32];
		hardRandom.nextBytes(salt);
		FileOutputStream fos = new FileOutputStream(masterKeysFile);
		long flags = 0;
		byte[] flagBytes = Fields.longToBytes(flags);
		byte[] data = new byte[flagBytes.length + clientCacheKey.length + HASH_LENGTH];
		System.arraycopy(flagBytes, 0, data, 0, flagBytes.length);
		System.arraycopy(clientCacheKey, 0, data, flagBytes.length, clientCacheKey.length);
		MessageDigest md = SHA256.getMessageDigest();
		md.update(data, 0, flagBytes.length + clientCacheKey.length);
		byte[] hash = md.digest();
		System.arraycopy(hash, 0, data, flagBytes.length + clientCacheKey.length, HASH_LENGTH);
		
//		System.err.println("Flag bytes: "+HexUtil.bytesToHex(flagBytes));
//		System.err.println("Client cache key: "+HexUtil.bytesToHex(clientCacheKey));
//		System.err.println("Hash: "+HexUtil.bytesToHex(clientCacheKey));
//		System.err.println("Data: "+HexUtil.bytesToHex(data));
		
		byte[] pwd = password.getBytes("UTF-8");
		md.update(pwd);
		md.update(salt);
		byte[] outerKey = md.digest();
//		System.err.println("Outer key: "+HexUtil.bytesToHex(outerKey));
		BlockCipher cipher;
		try {
			cipher = new Rijndael(256, 256);
		} catch (UnsupportedCipherException e) {
			// Impossible
			throw new Error(e);
		}
		cipher.initialize(outerKey);
		PCFBMode pcfb = PCFBMode.create(cipher, iv);
		pcfb.blockEncipher(data, 0, data.length);
//		System.err.println("Encrypted data: "+HexUtil.bytesToHex(data));
		fos.write(salt);
		fos.write(iv);
		fos.write(data);
		fos.close();
		clear(data);
		clear(hash);
		return new MasterKeys(clientCacheKey);
	}

	private static boolean arraysEqualTruncated(byte[] checkHash, byte[] hash, int length) {
		for(int i=0;i<length;i++) {
			if(checkHash[i] != hash[i]) return false;
		}
		return true;
	}

	public static void clear(byte[] buf) {
		for(int i=0;i<buf.length;i++)
			buf[i] = 0;
	}

	public void changePassword(String newPassword) {
		// TODO Auto-generated method stub
		
	}

}
