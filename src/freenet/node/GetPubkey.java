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
	private PubkeyStore pubKeyClientcache;
	
	GetPubkey() {
		cachedPubKeys = new LRUHashtable<ByteArrayWrapper, DSAPublicKey>();
	}

	void setDataStore(PubkeyStore pubKeyDatastore, PubkeyStore pubKeyDatacache) {
		this.pubKeyDatastore = pubKeyDatastore;
		this.pubKeyDatacache = pubKeyDatacache;
	}

	/**
	 * Get a public key by hash.
	 * @param hash The hash of the public key. Normally from an SSK.
	 * @param canReadClientCache If this is a local request, we can read the client-cache.
	 * @param canWriteDatastore If this is a request with high HTL, we can't promote it.
	 * @return A public key, or null.
	 */
	public DSAPublicKey getKey(byte[] hash, boolean canReadClientCache) {
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
			DSAPublicKey key = null;
			if(pubKeyClientcache != null && canReadClientCache)
				key = pubKeyClientcache.fetch(hash, false, false);
			// We can *read* from the datastore even if nearby, but we cannot promote in that case.
			if(key == null)
				key = pubKeyDatastore.fetch(hash, false, false);
			if (key == null)
				key = pubKeyDatacache.fetch(hash, false, false);
			if (key != null) {
				// Just put into the in-memory cache
				cacheKey(hash, key, false, false, false);
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
	 * Cache a public key.
	 * @param hash The hash of the public key.
	 * @param key The key to store.
	 * @param deep If true, we can store to the datastore rather than the cache.
	 * @param canWriteClientCache If true, we can write to the client-cache. Only set if the 
	 * request originated locally, and the client-cache option hasn't been turned off.
	 * @param canWriteDatastore If true, we cannot *write to* the store or the cache. This 
	 * happens for high initial HTL on both local requests and requests started relatively 
	 * nearby.
	 */
	public void cacheKey(byte[] hash, DSAPublicKey key, boolean deep, boolean canWriteClientCache, boolean canWriteDatastore) {
		if (logMINOR)
			Logger.minor(this, "Cache key: " + HexUtil.bytesToHex(hash) + " : " + key);
		ByteArrayWrapper w = new ByteArrayWrapper(hash);
		synchronized (cachedPubKeys) {
			DSAPublicKey key2 = cachedPubKeys.get(w);
			if ((key2 != null) && !key2.equals(key)) {
				// FIXME is this test really needed?
				// SHA-256 inside synchronized{} is a bad idea
				// FIXME get rid
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
							cacheKey(hash, key, deep, canWriteClientCache, canWriteDatastore);
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
			if (canWriteClientCache) {
				if(pubKeyClientcache != null) {
					pubKeyClientcache.put(hash, key);
					pubKeyClientcache.fetch(hash, true, false);
				}
			}
			// Cannot write to the store or cache if request started nearby.
			if(!canWriteDatastore) return;
			if (deep) {
				pubKeyDatastore.put(hash, key);
				pubKeyDatastore.fetch(hash, true, false);
			}
			pubKeyDatacache.put(hash, key);
			pubKeyDatacache.fetch(hash, true, false);
		} catch (IOException e) {
			// FIXME deal with disk full, access perms etc; tell user about it.
			Logger.error(this, "Error accessing pubkey store: " + e, e);
		}
	}

	public void setLocalDataStore(PubkeyStore pubKeyClientcache) {
		this.pubKeyClientcache = pubKeyClientcache;
	}
}
