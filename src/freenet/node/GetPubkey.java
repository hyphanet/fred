/**
 * Public Key Store (with LRU memory cache)
 */
package freenet.node;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;

import freenet.crypt.DSAPublicKey;
import freenet.crypt.SHA256;
import freenet.store.PubkeyStore;
import freenet.support.ByteArrayWrapper;
import freenet.support.HexUtil;
import freenet.support.LRUHashtable;
import freenet.support.Logger;

public class GetPubkey {
	private static volatile boolean logMINOR;

	static {
		Logger.registerClass(GetPubkey.class);
	}
	
	// Debugging stuff
	private static final boolean USE_RAM_PUBKEYS_CACHE = true;
	private static final int MAX_MEMORY_CACHED_PUBKEYS = 1000;
	
	private final LRUHashtable<ByteArrayWrapper, DSAPublicKey> cachedPubKeys;

	private PubkeyStore pubKeyDatastore;
	private PubkeyStore pubKeyDatacache;
	
	GetPubkey() {
		cachedPubKeys = new LRUHashtable<ByteArrayWrapper, DSAPublicKey>();
	}

	void setDataStore(PubkeyStore pubKeyDatastore, PubkeyStore pubKeyDatacache) {
		this.pubKeyDatastore = pubKeyDatastore;
		this.pubKeyDatacache = pubKeyDatacache;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see freenet.node.GetPubkey#getKey(byte[])
	 */
	public DSAPublicKey getKey(byte[] hash) {
		ByteArrayWrapper w = new ByteArrayWrapper(hash);
		if (logMINOR)
			Logger.minor(this, "Getting pubkey: " + HexUtil.bytesToHex(hash));

		if (USE_RAM_PUBKEYS_CACHE) {
			synchronized (cachedPubKeys) {
				DSAPublicKey key = cachedPubKeys.get(w);
				if (key != null) {
					cachedPubKeys.push(w, key);
					if (logMINOR)
						Logger.minor(this, "Got " + HexUtil.bytesToHex(hash) + " from cache");
					return key;
				}
			}
		}
		try {
			DSAPublicKey key;
			key = pubKeyDatastore.fetch(hash, false);
			if (key == null)
				key = pubKeyDatacache.fetch(hash, false);
			if (key != null) {
				cacheKey(hash, key, false);
				if (logMINOR)
					Logger.minor(this, "Got " + HexUtil.bytesToHex(hash) + " from store");
			}
			return key;
		} catch (IOException e) {
			// FIXME deal with disk full, access perms etc; tell user about it.
			Logger.error(this, "Error accessing pubkey store: " + e, e);
			return null;
		}
	}

	/**
	 * Cache a public key
	 */
	public void cacheKey(byte[] hash, DSAPublicKey key, boolean deep) {
		if (logMINOR)
			Logger.minor(this, "Cache key: " + HexUtil.bytesToHex(hash) + " : " + key);
		ByteArrayWrapper w = new ByteArrayWrapper(hash);
		synchronized (cachedPubKeys) {
			DSAPublicKey key2 = cachedPubKeys.get(w);
			if ((key2 != null) && !key2.equals(key)) {
				// FIXME is this test really needed?
				// SHA-256 inside synchronized{} is a bad idea
				MessageDigest md256 = SHA256.getMessageDigest();
				try {
					byte[] hashCheck = md256.digest(key.asBytes());
					if (Arrays.equals(hashCheck, hash)) {
						Logger.error(this, "Hash is correct!!!");
						// Verify the old key
						byte[] oldHash = md256.digest(key2.asBytes());
						if (Arrays.equals(oldHash, hash)) {
							Logger.error(this,
							        "Old hash is correct too!! - Bug in DSAPublicKey.equals() or SHA-256 collision!");
						} else {
							Logger.error(this, "Old hash is wrong!");
							cachedPubKeys.removeKey(w);
							cacheKey(hash, key, deep);
						}
					} else {
						Logger.error(this, "New hash is wrong");
					}
				} finally {
					SHA256.returnMessageDigest(md256);
				}
				throw new IllegalArgumentException("Wrong hash?? Already have different key with same hash!");
			}
			cachedPubKeys.push(w, key);
			while (cachedPubKeys.size() > MAX_MEMORY_CACHED_PUBKEYS)
				cachedPubKeys.popKey();
		}
		try {
			if (deep) {
				pubKeyDatastore.put(hash, key);
				pubKeyDatastore.fetch(hash, true);
			}
			pubKeyDatacache.put(hash, key);
			pubKeyDatacache.fetch(hash, true);
		} catch (IOException e) {
			// FIXME deal with disk full, access perms etc; tell user about it.
			Logger.error(this, "Error accessing pubkey store: " + e, e);
		}
	}
}
