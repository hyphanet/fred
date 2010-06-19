/**
 * Public Key Store (with LRU memory cache)
 */
package freenet.node;

import java.io.IOException;

import freenet.crypt.DSAPublicKey;
import freenet.store.BlockMetadata;
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
	private PubkeyStore pubKeySlashdotcache;
	
	private final Node node;
	
	GetPubkey(Node node) {
		cachedPubKeys = new LRUHashtable<ByteArrayWrapper, DSAPublicKey>();
		this.node = node;
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
	public DSAPublicKey getKey(byte[] hash, boolean canReadClientCache, boolean forULPR, BlockMetadata meta) {
		ByteArrayWrapper w = new ByteArrayWrapper(hash);
		if (logMINOR)
			Logger.minor(this, "Getting pubkey: " + HexUtil.bytesToHex(hash));

		if (USE_RAM_PUBKEYS_CACHE) {
			synchronized (cachedPubKeys) {
				DSAPublicKey key = cachedPubKeys.get(w);
				if (key != null) {
					cachedPubKeys.push(w, key);
					if (logMINOR)
						Logger.minor(this, "Got " + HexUtil.bytesToHex(hash) + " from in-memory cache");
					return key;
				}
			}
		}
		try {
			DSAPublicKey key = null;
			if(pubKeyClientcache != null && canReadClientCache)
				key = pubKeyClientcache.fetch(hash, false, meta);
			if(node.oldPKClientCache != null && canReadClientCache && key == null) {
				PubkeyStore pks = node.oldPKClientCache;
				if(pks != null) key = pks.fetch(hash, false, meta);
				if(key != null && logMINOR)
					Logger.minor(this, "Got "+HexUtil.bytesToHex(hash)+" from old client cache");
			}
			// We can *read* from the datastore even if nearby, but we cannot promote in that case.
			if(key == null) {
				key = pubKeyDatastore.fetch(hash, false, meta);
				if(key != null && logMINOR)
					Logger.minor(this, "Got "+HexUtil.bytesToHex(hash)+" from store");
			}
			if(key == null) {
				PubkeyStore pks = node.oldPK;
				if(pks != null) key = pks.fetch(hash, false, meta);
				if(key != null && logMINOR)
					Logger.minor(this, "Got "+HexUtil.bytesToHex(hash)+" from old store");
			}
			if (key == null) {
				key = pubKeyDatacache.fetch(hash, false, meta);
				if(key != null && logMINOR)
					Logger.minor(this, "Got "+HexUtil.bytesToHex(hash)+" from cache");
			}
			if(key == null) {
				PubkeyStore pks = node.oldPKCache;
				if(pks != null) key = pks.fetch(hash, false, meta);
				if(key != null && logMINOR)
					Logger.minor(this, "Got "+HexUtil.bytesToHex(hash)+" from old cache");
			}
			if(key == null && pubKeySlashdotcache != null && forULPR) {
				key = pubKeySlashdotcache.fetch(hash, false, meta);
				if (logMINOR)
					Logger.minor(this, "Got " + HexUtil.bytesToHex(hash) + " from slashdot cache");
			}
			if (key != null) {
				// Just put into the in-memory cache
				cacheKey(hash, key, false, false, false, false, false);
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
	 * @param canWriteDatastore If false, we cannot *write to* the store or the cache. This 
	 * happens for high initial HTL on both local requests and requests started relatively 
	 * nearby.
	 * @param forULPR 
	 */
	public void cacheKey(byte[] hash, DSAPublicKey key, boolean deep, boolean canWriteClientCache, boolean canWriteDatastore, boolean forULPR, boolean writeLocalToDatastore) {
		if (logMINOR)
			Logger.minor(this, "Cache key: " + HexUtil.bytesToHex(hash) + " : " + key);
		ByteArrayWrapper w = new ByteArrayWrapper(hash);
		synchronized (cachedPubKeys) {
			DSAPublicKey key2 = cachedPubKeys.get(w);
			if ((key2 != null) && !key2.equals(key))
				throw new IllegalArgumentException("Wrong hash?? Already have different key with same hash!");
			cachedPubKeys.push(w, key);
			while (cachedPubKeys.size() > MAX_MEMORY_CACHED_PUBKEYS)
				cachedPubKeys.popKey();
		}
		try {
			if (canWriteClientCache && !(canWriteDatastore || writeLocalToDatastore)) {
				if(pubKeyClientcache != null) {
					pubKeyClientcache.put(hash, key, false);
				}
			}
			if (forULPR && !(canWriteDatastore || writeLocalToDatastore)) {
				if(pubKeySlashdotcache!= null) {
					pubKeySlashdotcache.put(hash, key, false);
				}
			}
			// Cannot write to the store or cache if request started nearby.
			if(!(canWriteDatastore || writeLocalToDatastore)) return;
			if (deep) {
				pubKeyDatastore.put(hash, key, !canWriteDatastore);
			}
			pubKeyDatacache.put(hash, key, !canWriteDatastore);
		} catch (IOException e) {
			// FIXME deal with disk full, access perms etc; tell user about it.
			Logger.error(this, "Error accessing pubkey store: " + e, e);
		}
	}

	public void setLocalDataStore(PubkeyStore pubKeyClientcache) {
		this.pubKeyClientcache = pubKeyClientcache;
	}
	
	public void setLocalSlashdotcache(PubkeyStore pubKeySlashdotcache) {
		this.pubKeySlashdotcache = pubKeySlashdotcache;
	}
}
