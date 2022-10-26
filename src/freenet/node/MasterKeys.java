package freenet.node;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;

import freenet.crypt.BlockCipher;
import freenet.crypt.MasterSecret;
import freenet.crypt.PCFBMode;
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
	private final byte[] databaseKey;
	private final byte[] tempfilesMasterSecret;
	final long flags;

	final static long FLAG_ENCRYPT_DATABASE = 2;

	public MasterKeys(byte[] clientCacheKey, byte[] databaseKey, byte[] tempfilesMasterSecret, long flags) {
		this.clientCacheMasterKey = clientCacheKey;
		this.databaseKey = databaseKey;
		this.flags = flags;
		this.tempfilesMasterSecret = tempfilesMasterSecret;
	}
	
    /** Create a MasterKeys with random keys.
     * @param random A secure RNG. Not specifically a SecureRandom because we want to be able to 
     * use this in tests. */
    public static MasterKeys createRandom(Random random) {
        byte[] clientCacheKey = new byte[32];
        random.nextBytes(clientCacheKey);
        byte[] databaseKey = new byte[32];
        random.nextBytes(databaseKey);
        byte[] tempfilesMasterSecret = new byte[64];
        random.nextBytes(tempfilesMasterSecret);
        return new MasterKeys(clientCacheKey, databaseKey, tempfilesMasterSecret, 0);
    }

    void clearClientCacheKeys() {
		clear(clientCacheMasterKey);
	}

	static final int OLD_HASH_LENGTH = 4;
	static final int HASH_LENGTH = 12;
	
	static final int VERSION = 1;
	
	/** Sanity check */
	static final long MAX_ITERATIONS = 1L << 40;
	
	/** Time in milliseconds to iterate for when encrypting a non-empty password. 
	 * FIXME make this configurable. FIXME Have a look at real password to key functions. */
	static int ITERATE_TIME = 1000;

	public static MasterKeys read(File masterKeysFile, Random hardRandom, String password) throws MasterKeysWrongPasswordException, MasterKeysFileSizeException, IOException {
		System.err.println("Trying to read master keys file...");
		if(masterKeysFile != null && masterKeysFile.exists()) {
			// Try to read the keys
			FileInputStream fis = null;
			// FIXME move declarations of sensitive data out and clear() in finally {}
			long len = masterKeysFile.length();
            if(len > 1024) throw new MasterKeysFileSizeException(true);
            if(len < (32 + 32 + 8 + 32)) throw new MasterKeysFileSizeException(false);
			int length = (int) len;
			try {
				fis = new FileInputStream(masterKeysFile);
				DataInputStream dis = new DataInputStream(fis);
				if(len == 140) {
				    MasterKeys ret = readOldFormat(dis, length, hardRandom, password);
				    System.out.println("Read old-format master keys file. Writing new format master.keys ...");
                    ret.changePassword(masterKeysFile, password, hardRandom);
                    return ret;
				}
				if(dis.readInt() != VERSION) throw new IOException("Bad version for master.keys");
				long iterations = dis.readLong();
				if(iterations < 0 || iterations > MAX_ITERATIONS) throw new IOException("Bad iterations "+iterations+" for master.keys");
				
				byte[] salt = new byte[32];
				dis.readFully(salt);
				byte[] iv = new byte[32];
				dis.readFully(iv);
				byte[] dataAndHash = new byte[length - salt.length - iv.length - 4 - 8];
				dis.readFully(dataAndHash);
//				System.err.println("Data and hash: "+HexUtil.bytesToHex(dataAndHash));
				byte[] pwd = password.getBytes("UTF-8");
				MessageDigest md = SHA256.getMessageDigest();
				md.update(pwd);
				md.update(salt);
				byte[] outerKey = md.digest();
				if(iterations > 0) {
				    System.out.println("Decrypting master keys using password with "+iterations+" iterations...");
				    for(long i=0;i<iterations;i++) {
				        md.update(salt);
				        md.update(outerKey);
				        outerKey = md.digest();
				    }
				}
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
				long flags = dis.readLong();
				// At the moment there are no interesting flags.
				// In future the flags will tell us whether the database and the datastore are encrypted.
				byte[] clientCacheKey = new byte[32];
				dis.readFully(clientCacheKey);
				byte[] databaseKey = null;
				databaseKey = new byte[32];
				dis.readFully(databaseKey);
				byte[] tempfilesMasterSecret = new byte[64];
				boolean mustWrite = false;
				if(data.length >= 8+32+32+64) {
				    dis.readFully(tempfilesMasterSecret);
				} else {
                    System.err.println("Created new master secret for encrypted tempfiles");
				    hardRandom.nextBytes(tempfilesMasterSecret);
				    mustWrite = true;
				}
				MasterKeys ret = new MasterKeys(clientCacheKey, databaseKey, tempfilesMasterSecret, flags);
				clear(data);
				clear(hash);
				SHA256.returnMessageDigest(md);
				System.err.println("Read old master keys file");
				if(mustWrite) {
				    ret.changePassword(masterKeysFile, password, hardRandom);
				}
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
		MasterKeys ret = createRandom(hardRandom);
		ret.write(masterKeysFile, password, hardRandom);
		return ret;
	}

	private static MasterKeys readOldFormat(DataInputStream dis, int length, Random hardRandom,
            String password) throws IOException, MasterKeysWrongPasswordException {
        byte[] salt = new byte[32];
        dis.readFully(salt);
        byte[] iv = new byte[32];
        dis.readFully(iv);
        byte[] dataAndHash = new byte[length - salt.length - iv.length];
        dis.readFully(dataAndHash);
//      System.err.println("Data and hash: "+HexUtil.bytesToHex(dataAndHash));
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
//      System.err.println("Outer key: "+HexUtil.bytesToHex(outerKey));
        cipher.initialize(outerKey);
        PCFBMode pcfb = PCFBMode.create(cipher, iv);
        pcfb.blockDecipher(dataAndHash, 0, dataAndHash.length);
//      System.err.println("Decrypted data and hash: "+HexUtil.bytesToHex(dataAndHash));
        byte[] data = Arrays.copyOf(dataAndHash, dataAndHash.length - OLD_HASH_LENGTH);
        byte[] hash = Arrays.copyOfRange(dataAndHash, data.length, dataAndHash.length);
//      System.err.println("Data: "+HexUtil.bytesToHex(data));
//      System.err.println("Hash: "+HexUtil.bytesToHex(hash));
        clear(dataAndHash);
        byte[] checkHash = md.digest(data);
//      System.err.println("Check hash: "+HexUtil.bytesToHex(checkHash));
        if(!Fields.byteArrayEqual(checkHash, hash, 0, 0, OLD_HASH_LENGTH)) {
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
        byte[] tempfilesMasterSecret = new byte[64];
        System.err.println("Created new master secret for encrypted tempfiles");
        hardRandom.nextBytes(tempfilesMasterSecret);
        MasterKeys ret = new MasterKeys(clientCacheKey, databaseKey, tempfilesMasterSecret, flags);
        clear(data);
        clear(hash);
        SHA256.returnMessageDigest(md);
        return ret;
    }

    public static void clear(byte[] buf) {
		if(buf == null) return; // Valid no-op, simplifies code
		Arrays.fill(buf, (byte)0x00);
	}

	public void changePassword(File masterKeysFile, String newPassword, Random hardRandom) throws IOException {
		System.err.println("Writing new master.keys file");
		write(masterKeysFile, newPassword, hardRandom);
	}
	
	private void write(File masterKeysFile, String newPassword, Random hardRandom) throws IOException {
		// Write it to a byte[], check size, then replace in-place atomically

		// New IV, new salt, same client cache key, same database key

	    ByteArrayOutputStream baos = new ByteArrayOutputStream();
	    
		byte[] iv = new byte[32];
		hardRandom.nextBytes(iv);
		byte[] salt = new byte[32];
		hardRandom.nextBytes(salt);

        byte[] pwd;
        try {
            pwd = newPassword.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            // Impossible
            throw new Error(e);
        }
        MessageDigest md = SHA256.getMessageDigest();
        md.update(pwd);
        md.update(salt);
        byte[] outerKey = md.digest();
        long iterations = 0;
        if(!newPassword.equals("")) {
            long startTime = System.currentTimeMillis();
            while(System.currentTimeMillis() < startTime + ITERATE_TIME && iterations < MAX_ITERATIONS-20) {
                for(int i=0;i<10;i++) {
                    iterations++;
                    md.update(salt);
                    md.update(outerKey);
                    outerKey = md.digest();
                }
            }
            System.out.println("Encrypted password with "+iterations+" iterations.");
        }

		DataOutputStream dos = new DataOutputStream(baos);
		dos.writeInt(VERSION);
		dos.writeLong(iterations);
		baos.write(salt);
		baos.write(iv);
		int hashedStart = salt.length + iv.length + 4 + 8;
		dos.writeLong(flags);
		baos.write(clientCacheMasterKey);
		baos.write(databaseKey);
		baos.write(tempfilesMasterSecret);
		
		byte[] data = baos.toByteArray();
		
		md.update(data, hashedStart, data.length-hashedStart);
		byte[] hash = md.digest();
        SHA256.returnMessageDigest(md); md = null;
		baos.write(hash, 0, HASH_LENGTH);
		data = baos.toByteArray();

		BlockCipher cipher;
		try {
			cipher = new Rijndael(256, 256);
		} catch (UnsupportedCipherException e) {
			// Impossible
			throw new Error(e);
		}
		cipher.initialize(outerKey);
		PCFBMode pcfb = PCFBMode.create(cipher, iv);
		pcfb.blockEncipher(data, hashedStart, data.length - hashedStart);

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

	public static void killMasterKeys(File masterKeysFile) throws IOException {
		FileUtil.secureDelete(masterKeysFile);
	}

	public DatabaseKey createDatabaseKey(Random random) {
	    return new DatabaseKey(databaseKey, random);
	}

	/** Used for creating keys for persistent encrypted tempfiles */
    public MasterSecret getPersistentMasterSecret() {
        return new MasterSecret(tempfilesMasterSecret.clone());
    }

}
