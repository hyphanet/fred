package freenet.client;

import freenet.keys.FreenetURI;
import freenet.support.LRUMap;
import freenet.support.api.Bucket;
import freenet.support.io.MultiReaderBucket;

class ArchiveBucketCache {
    /**
     * Maximum number of cached buckets.
     */
    private final int maxBuckets;

    /**
     * Maximum cached data in bytes.
     */
    private final long maxBytes;

    /**
     * Underlying cached items by cache key (as [uri]:[filename]).
     */
    private final LRUMap<String, CachedBucket> cache = new LRUMap<>();

    /**
     * Currently cached data in bytes.
     */
    private long currentBytes;

    ArchiveBucketCache(int maxBuckets, long maxBytes) {
        this.maxBuckets = maxBuckets;
        this.maxBytes = maxBytes;
    }

    /**
     * Add an item to the cache and return a claimed bucket for the same item.
     * The returned bucket (if not null) must be freed by the caller.
     *
     * @return a read-only bucket referencing the cached data, or null if the entry was not found
     */
    synchronized Bucket acquire(FreenetURI uri, String filename) {
        String key = createCacheKey(uri, filename);
        CachedBucket item = cache.get(key);
        if (item != null) {
            // Promote the item to the top of the LRU.
            cache.push(key, item);
            // Acquire the bucket while holding lock to ensure the item is not yet released.
            return acquire(item);
        }
        return null;
    }

    /**
     * Add an item to the cache and return an acquired bucket for the same item.
     * The cache assumes responsibility of freeing the provided bucket, the caller should not free it.
     * The returned bucket must eventually be freed by the caller.
     *
     * @return a read-only bucket referencing the same data as the provided bucket
     */
    synchronized Bucket addAndAcquire(FreenetURI uri, String filename, Bucket bucket) {
        // Store the item in the cache, it will be released when it gets evicted from the cache.
        String key = createCacheKey(uri, filename);
        CachedBucket item = new CachedBucket(bucket);
        CachedBucket oldItem = cache.push(key, item);
        onAdded(item);

        // Acquire the bucket now to keep the item alive, even if the item is evicted immediately after we return.
        Bucket acquired = acquire(item);

        // Cleanup the evicted item (if any) and evict the least recently items to stay within size limits.
        onEvicted(oldItem);
        evictLeastRecentlyUsedItems();

        return acquired;
    }

    private void evictLeastRecentlyUsedItems() {
        while (!cache.isEmpty() && (currentBytes > maxBytes || cache.size() > maxBuckets)) {
            CachedBucket oldItem = cache.popValue();
            onEvicted(oldItem);
        }
    }

    private void onAdded(CachedBucket item) {
        currentBytes += item.size;
    }

    private void onEvicted(CachedBucket item) {
        if (item != null) {
            currentBytes -= item.size;
            item.keepAliveReference.free();
        }
    }

    private static Bucket acquire(CachedBucket item) {
        return item.multiReaderBucket.getReaderBucket();
    }

    private static String createCacheKey(FreenetURI uri, String filename) {
        return uri.toASCIIString() + ":" + filename;
    }

    private static class CachedBucket {
        private final MultiReaderBucket multiReaderBucket;
        private final Bucket keepAliveReference;
        private final long size;

        private CachedBucket(Bucket bucket) {
            this.multiReaderBucket = new MultiReaderBucket(bucket);
            this.keepAliveReference = multiReaderBucket.getReaderBucket();
            this.size = bucket.size();
        }
    }
}
