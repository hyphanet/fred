package freenet.store;

import freenet.crypt.DSAPublicKey;

public interface GetPubkey {

	/**
	 * Get a public key by hash.
	 * @param hash The hash of the public key. Normally from an SSK.
	 * @param canReadClientCache If this is a local request, we can read the client-cache.
	 * @param canWriteDatastore If this is a request with high HTL, we can't promote it.
	 * @return A public key, or null.
	 */
	public abstract DSAPublicKey getKey(byte[] hash,
			boolean canReadClientCache, boolean forULPR, BlockMetadata meta);

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
	public abstract void cacheKey(byte[] hash, DSAPublicKey key, boolean deep,
			boolean canWriteClientCache, boolean canWriteDatastore,
			boolean forULPR, boolean writeLocalToDatastore);

}