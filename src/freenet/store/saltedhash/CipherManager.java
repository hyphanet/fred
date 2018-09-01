/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.store.saltedhash;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import freenet.crypt.BlockCipher;
import freenet.crypt.PCFBMode;
import freenet.crypt.SHA256;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.ciphers.Rijndael;
import freenet.node.MasterKeys;
import freenet.support.ByteArrayWrapper;
import freenet.support.Logger;

/**
 * Cipher Manager
 * 
 * Manage all kind of digestion and encryption in store
 * 
 * @author sdiz
 */
public class CipherManager {
	/**
	 * The actual salt. 16 bytes.
	 */
	private byte[] salt;

	/**
	 * The original on-disk salt, may be encrypted. 16 bytes.
	 */
	private byte[] diskSalt;

	CipherManager(byte[] salt, byte[] diskSalt) {
		assert salt.length == 0x10;
		this.salt = salt;
		this.diskSalt = diskSalt;
	}

	/**
	 * Get salt
	 * 
	 * @return salt
	 */
	byte[] getDiskSalt() {
		return diskSalt;
	}

	/**
	 * Cache for digested keys
	 */
	@SuppressWarnings("serial")
	private Map<ByteArrayWrapper, byte[]> digestRoutingKeyCache = new LinkedHashMap<ByteArrayWrapper, byte[]>() {
		@Override
		protected boolean removeEldestEntry(Map.Entry<ByteArrayWrapper, byte[]> eldest) {
			return size() > 128;
		}
	};

	/**
	 * Get digested routing key
	 * 
	 * @param plainKey
	 * @return
	 */
	byte[] getDigestedKey(byte[] plainKey) {
		ByteArrayWrapper key = new ByteArrayWrapper(plainKey);
		synchronized (digestRoutingKeyCache) {
			byte[] dk = digestRoutingKeyCache.get(key);
			if (dk != null)
				return dk;
		}

		MessageDigest digest = SHA256.getMessageDigest();
		try {
			digest.update(plainKey);
			digest.update(salt);

			byte[] hashedRoutingKey = digest.digest();
			assert hashedRoutingKey.length == 0x20;

			synchronized (digestRoutingKeyCache) {
				digestRoutingKeyCache.put(key, hashedRoutingKey);
			}

			return hashedRoutingKey;
		} finally {
			SHA256.returnMessageDigest(digest);
		}
	}

	/**
	 * Encrypt this entry
	 */
	void encrypt(SaltedHashFreenetStore<?>.Entry entry, Random random) {
		if (entry.isEncrypted)
			return;

		entry.dataEncryptIV = new byte[16];
		random.nextBytes(entry.dataEncryptIV);

		PCFBMode cipher = makeCipher(entry.dataEncryptIV, entry.plainRoutingKey);
		cipher.blockEncipher(entry.header, 0, entry.header.length);
		cipher.blockEncipher(entry.data, 0, entry.data.length);

		entry.getDigestedRoutingKey();
		entry.isEncrypted = true;
	}

	/**
	 * Verify and decrypt this entry
	 * 
	 * @param routingKey
	 * @return <code>true</code> if the <code>routeKey</code> match and the entry is decrypted.
	 */
	boolean decrypt(SaltedHashFreenetStore<?>.Entry entry, byte[] routingKey) {
		assert entry.header != null;
		assert entry.data != null;

		if (!entry.isEncrypted) {
			// Already decrypted
			if (Arrays.equals(entry.plainRoutingKey, routingKey))
				return true;
			else
				return false;
		}

		if (entry.plainRoutingKey != null) {
			// we knew the key
			if (!Arrays.equals(entry.plainRoutingKey, routingKey)) {
				return false;
			}
		} else {
			// we do not know the plain key, let's check the digest
			if (!Arrays.equals(entry.digestedRoutingKey, getDigestedKey(routingKey)))
				return false;
		}

		entry.plainRoutingKey = routingKey;

		PCFBMode cipher = makeCipher(entry.dataEncryptIV, entry.plainRoutingKey);
		cipher.blockDecipher(entry.header, 0, entry.header.length);
		cipher.blockDecipher(entry.data, 0, entry.data.length);

		entry.isEncrypted = false;

		return true;
	}

	/**
	 * Create PCFBMode object for this key
	 */
	PCFBMode makeCipher(byte[] iv, byte[] key) {
		byte[] iv2 = new byte[0x20]; // 256 bits

		System.arraycopy(salt, 0, iv2, 0, 0x10);
		System.arraycopy(iv, 0, iv2, 0x10, 0x10);

		try {
			BlockCipher aes = new Rijndael(256, 256);
			aes.initialize(key);

			return PCFBMode.create(aes, iv2);
		} catch (UnsupportedCipherException e) {
			Logger.error(this, "Rijndael not supported!", e);
			throw new Error("Rijndael not supported!", e);
		}
	}

	public void shutdown() {
		MasterKeys.clear(salt);
		MasterKeys.clear(diskSalt);
	}
}
