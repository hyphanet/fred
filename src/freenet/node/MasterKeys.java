package freenet.node;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.util.Arrays;

import freenet.crypt.BlockCipher;
import freenet.crypt.PCFBMode;
import freenet.crypt.RandomSource;
import freenet.crypt.SHA256;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.ciphers.Rijndael;
import freenet.support.Fields;
import freenet.support.io.Closer;
import freenet.support.io.FileUtil;

/** Keys read from the master keys file */
public class MasterKeys {

	// Currently we only encrypt the client cache

	final byte[] clientCacheMasterKey;
	final byte[] databaseKey;
	final long flags;

	final static long FLAG_ENCRYPT_DATABASE = 2;

	public MasterKeys(byte[] clientCacheKey, byte[] databaseKey, long flags) {
		this.clientCacheMasterKey = clientCacheKey;
		this.databaseKey = databaseKey;
		this.flags = flags;
	}

	void clearClientCacheKeys() {
		clear(clientCacheMasterKey);
	}

	static final int HASH_LENGTH = 4;

	public static MasterKeys read(File masterKeysFile, RandomSource hardRandom, String password) throws MasterKeysWrongPasswordException, MasterKeysFileSizeException, IOException {
		System.err.println("Trying to read master keys file...");
		if(masterKeysFile != null) {
			// Try to read the keys
			FileInputStream fis = null;
			// FIXME move declarations of sensitive data out and clear() in finally {}
			try {
				fis = new FileInputStream(masterKeysFile);
				DataInputStream dis = new DataInputStream(fis);
				byte[] salt = new byte[32];
				dis.readFully(salt);
				byte[] iv = new byte[32];
				dis.readFully(iv);
				int length = (int)Math.min(Integer.MAX_VALUE, masterKeysFile.length());
				if(masterKeysFile.length() > 1024) throw new MasterKeysFileSizeException(true);
				if(masterKeysFile.length() < (32 + 32 + 8 + 32)) throw new MasterKeysFileSizeException(false);
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
				byte[] data = Arrays.copyOf(dataAndHash, dataAndHash.length - HASH_LENGTH);
				byte[] hash = Arrays.copyOfRange(dataAndHash, data.length, dataAndHash.length);
//				System.err.println("Data: "+HexUtil.bytesToHex(data));
//				System.err.println("Hash: "+HexUtil.bytesToHex(hash));
				clear(dataAndHash);
				byte[] checkHash = md.digest(data);
//				System.err.println("Check hash: "+HexUtil.bytesToHex(checkHash));
				if(!Fields.byteArrayEqual(checkHash, hash, 0, 0, HASH_LENGTH)) {
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
				byte[] databaseKey = null;
				databaseKey = new byte[32];
				dis.readFully(databaseKey);
				MasterKeys ret = new MasterKeys(clientCacheKey, databaseKey, flags);
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
				throw new MasterKeysFileSizeException(false);
			} finally {
				Closer.close(fis);
			}
		}
		System.err.println("Creating new master keys file");
		// If we are still here, we need to create a new master keys file
		// FIXME try{}, move declarations of sensitive data out, clear() in finally {}
		byte[] clientCacheKey = new byte[32];
		hardRandom.nextBytes(clientCacheKey);
		byte[] databaseKey = new byte[32];
		hardRandom.nextBytes(databaseKey);
		byte[] iv = new byte[32];
		hardRandom.nextBytes(iv);
		byte[] salt = new byte[32];
		hardRandom.nextBytes(salt);
		FileOutputStream fos = new FileOutputStream(masterKeysFile);
		long flags = 0;
		byte[] flagBytes = Fields.longToBytes(flags);
		byte[] data = new byte[flagBytes.length + clientCacheKey.length + databaseKey.length + HASH_LENGTH];
		int offset = 0;
		System.arraycopy(flagBytes, 0, data, offset, flagBytes.length);
		offset += flagBytes.length;
		System.arraycopy(clientCacheKey, 0, data, offset, clientCacheKey.length);
		offset += clientCacheKey.length;
		System.arraycopy(databaseKey, 0, data, offset, databaseKey.length);
		offset += databaseKey.length;
		MessageDigest md = SHA256.getMessageDigest();
		md.update(data, 0, offset);
		byte[] hash = md.digest();
		System.arraycopy(hash, 0, data, offset, HASH_LENGTH);
		offset += HASH_LENGTH;
		/* assert(offset == data.length); */

//		System.err.println("Flag bytes: "+HexUtil.bytesToHex(flagBytes));
//		System.err.println("Client cache key: "+HexUtil.bytesToHex(clientCacheKey));
//		System.err.println("Hash: "+HexUtil.bytesToHex(clientCacheKey));
//		System.err.println("Data: "+HexUtil.bytesToHex(data));

		byte[] pwd = password.getBytes("UTF-8");
		md.update(pwd);
		md.update(salt);
		byte[] outerKey = md.digest();
		SHA256.returnMessageDigest(md); md = null;
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
		return new MasterKeys(clientCacheKey, databaseKey, flags);
	}

	public static void clear(byte[] buf) {
		if(buf == null) return; // Valid no-op, simplifies code
		Arrays.fill(buf, (byte)0x00);
	}

	public void changePassword(File masterKeysFile, String newPassword, RandomSource hardRandom) throws IOException {
		System.err.println("Writing new master.keys file");
		// Write it to a byte[], check size, then replace in-place atomically

		// New IV, new salt, same client cache key, same database key

		byte[] iv = new byte[32];
		hardRandom.nextBytes(iv);
		byte[] salt = new byte[32];
		hardRandom.nextBytes(salt);

		byte[] flagBytes = Fields.longToBytes(flags);

		byte[] data = new byte[iv.length + salt.length + flagBytes.length + clientCacheMasterKey.length + databaseKey.length + HASH_LENGTH];

		int offset = 0;
		System.arraycopy(salt, 0, data, offset, salt.length);
		offset += salt.length;
		System.arraycopy(iv, 0, data, offset, iv.length);
		offset += iv.length;

		int hashedStart = offset;
		System.arraycopy(flagBytes, 0, data, offset, flagBytes.length);
		offset += flagBytes.length;
		System.arraycopy(clientCacheMasterKey, 0, data, offset, clientCacheMasterKey.length);
		offset += clientCacheMasterKey.length;
		System.arraycopy(databaseKey, 0, data, offset, databaseKey.length);
		offset += databaseKey.length;
		MessageDigest md = SHA256.getMessageDigest();
		md.update(data, hashedStart, offset-hashedStart);
		byte[] hash = md.digest();
		System.arraycopy(hash, 0, data, offset, HASH_LENGTH);
		offset += HASH_LENGTH;
		/* assert(offset == data.length); */

		byte[] pwd;
		try {
			pwd = newPassword.getBytes("UTF-8");
		} catch (UnsupportedEncodingException e) {
			// Impossible
			throw new Error(e);
		}
		md.update(pwd);
		md.update(salt);
		byte[] outerKey = md.digest();
		SHA256.returnMessageDigest(md); md = null;

		BlockCipher cipher;
		try {
			cipher = new Rijndael(256, 256);
		} catch (UnsupportedCipherException e) {
			// Impossible
			throw new Error(e);
		}
		cipher.initialize(outerKey);
		PCFBMode pcfb = PCFBMode.create(cipher, iv);
		pcfb.blockEncipher(data, iv.length + salt.length, data.length - iv.length - salt.length);

		RandomAccessFile raf = new RandomAccessFile(masterKeysFile, "rw");

		raf.seek(0);
		raf.write(data);
		long len = raf.length();
		if(len > data.length) {
			byte[] diff = new byte[(int)(len - data.length)];
			raf.write(diff);
			raf.setLength(data.length);
		}
		raf.getFD().sync();
		raf.close();
	}

	public static void killMasterKeys(File masterKeysFile, RandomSource random) throws IOException {
		FileUtil.secureDelete(masterKeysFile, random);
	}

	public void clearAllNotClientCacheKey() {
		clear(databaseKey);
	}

	public void clearAllNotDatabaseKey() {
		clear(clientCacheMasterKey);
	}

	public void clearAll() {
		clear(clientCacheMasterKey);
		clear(databaseKey);
	}

	public void clearAllNotClientCacheKeyOrDatabaseKey() {
		// Do nothing. For now.
	}

}
