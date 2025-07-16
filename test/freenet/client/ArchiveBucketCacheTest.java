package freenet.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import freenet.keys.FreenetURI;
import freenet.support.api.Bucket;
import freenet.support.io.ArrayBucket;
import freenet.support.io.FileUtil;
import org.junit.Test;

public class ArchiveBucketCacheTest {

    private final FreenetURI archiveUri = new FreenetURI("KSK", "archive");
    private final String originalContent = "archive file contents";
    private final Bucket originalBucket = new ArrayBucket(originalContent.getBytes(StandardCharsets.UTF_8));

    private final ArchiveBucketCache singleBucketCache = new ArchiveBucketCache(1, Integer.MAX_VALUE);

    @Test
    public void acquireReturnsNullWhenNotFound() {
        Bucket acquired = singleBucketCache.acquire(archiveUri, "file");
        assertNull("Acquired bucket should be null", acquired);
    }

    @Test
    public void addAndAcquiredBucketWithReadOnlyReferenceToOriginal() {
        Bucket acquired = singleBucketCache.addAndAcquire(archiveUri, "file", originalBucket);
        assertTrue("Acquired bucket should be read-only", acquired.isReadOnly());
        assertFalse("Acquired bucket should not be freed", isFreed(acquired));
        assertEquals("Acquired bucket should reflect original content", originalContent, readFully(acquired));
    }

    @Test
    public void acquireBucketWithReadOnlyReferenceToOriginal() {
        singleBucketCache.addAndAcquire(archiveUri, "file", originalBucket).free();
        Bucket acquired = singleBucketCache.acquire(new FreenetURI("KSK", "archive"), "file");
        assertTrue("Acquired bucket should be read-only", acquired.isReadOnly());
        assertFalse("Acquired bucket should not be freed", isFreed(acquired));
        assertEquals("Acquired bucket should reflect original content", originalContent, readFully(acquired));
    }

    @Test
    public void freeUnreferencedBucketWhenEvicted() {
        // Acquire the first entry and free it immediately
        Bucket aqcuired = singleBucketCache.addAndAcquire(archiveUri, "file", originalBucket);
        aqcuired.free();
        assertFalse("Original bucket should not be freed", isFreed(originalBucket));

        // Add another entry to cause eviction of the first
        singleBucketCache.addAndAcquire(archiveUri, "other", new ArrayBucket());
        assertTrue("Original bucket should be freed", isFreed(originalBucket));
    }

    @Test
    public void freeEvictedBucketWhenUnreferenced() {
        // Acquire the first and then add another entry to cause eviction of the first
        Bucket aqcuired = singleBucketCache.addAndAcquire(archiveUri, "file", originalBucket);
        singleBucketCache.addAndAcquire(archiveUri, "other", new ArrayBucket());
        assertFalse("Original bucket should not be freed", isFreed(originalBucket));

        // Free the reference to the evicted entry
        aqcuired.free();
        assertTrue("Original bucket should be freed", isFreed(originalBucket));
    }

    @Test
    public void freeEvictedBucketWhenAllUnreferenced() {
        Bucket aqcuired1 = singleBucketCache.addAndAcquire(archiveUri, "file", originalBucket);
        Bucket aqcuired2 = singleBucketCache.acquire(archiveUri, "file");
        singleBucketCache.addAndAcquire(archiveUri, "other", new ArrayBucket());

        aqcuired1.free();
        assertFalse("Original bucket should not be freed", isFreed(originalBucket));

        aqcuired2.free();
        assertTrue("Original bucket should be freed", isFreed(originalBucket));
    }

    @Test
    public void evictsLeastRecentlyUsed() {
        ArchiveBucketCache threeBucketCache = new ArchiveBucketCache(3, Integer.MAX_VALUE);
        threeBucketCache.addAndAcquire(archiveUri, "file-1", originalBucket);
        threeBucketCache.addAndAcquire(archiveUri, "file-2", originalBucket);
        threeBucketCache.addAndAcquire(archiveUri, "file-3", originalBucket);

        threeBucketCache.acquire(archiveUri, "file-1"); // file-2 is now least recently used
        threeBucketCache.addAndAcquire(archiveUri, "file-4", originalBucket); // trigger eviction by adding 4th item
        assertNull("Entry file-2 should be evicted", threeBucketCache.acquire(archiveUri, "file-2"));
        assertNotNull("Entry file-1 should be cached", threeBucketCache.acquire(archiveUri, "file-1"));
        assertNotNull("Entry file-3 should be cached", threeBucketCache.acquire(archiveUri, "file-3"));
        assertNotNull("Entry file-4 should be cached", threeBucketCache.acquire(archiveUri, "file-4"));
    }

    @Test
    public void evictsBasedOnSize() {
        ArchiveBucketCache threeByteCache = new ArchiveBucketCache(Integer.MAX_VALUE, 3);
        threeByteCache.addAndAcquire(archiveUri, "small-file-1", new ArrayBucket(new byte[1]));
        threeByteCache.addAndAcquire(archiveUri, "small-file-2", new ArrayBucket(new byte[1]));
        threeByteCache.addAndAcquire(archiveUri, "small-file-3", new ArrayBucket(new byte[1]));
        assertNotNull("Entry small-file-1 should be cached", threeByteCache.acquire(archiveUri, "small-file-1"));
        assertNotNull("Entry small-file-2 should be cached", threeByteCache.acquire(archiveUri, "small-file-2"));
        assertNotNull("Entry small-file-3 should be cached", threeByteCache.acquire(archiveUri, "small-file-3"));

        threeByteCache.addAndAcquire(archiveUri, "large-file", new ArrayBucket(new byte[2]));
        assertNotNull("Entry large-file should be cached", threeByteCache.acquire(archiveUri, "large-file"));
        assertNotNull("Entry small-file-3 should be cached", threeByteCache.acquire(archiveUri, "small-file-3"));
        assertNull("Entry small-file-1 should be evicted", threeByteCache.acquire(archiveUri, "small-file-1"));
        assertNull("Entry small-file-2 should be evicted", threeByteCache.acquire(archiveUri, "small-file-2"));
    }

    private static String readFully(Bucket bucket) {
        try (InputStream in = bucket.getInputStream()) {
            return FileUtil.readUTF(in).toString();
        } catch (IOException e) {
            throw new RuntimeException("Could not read bucket contents", e);
        }
    }

    private static boolean isFreed(Bucket bucket) {
        try {
            Field freed = bucket.getClass().getDeclaredField("freed");
            freed.setAccessible(true);
            return (boolean) freed.get(bucket);
        } catch (Exception e) {
            throw new RuntimeException("Could not read bucket freed status", e);
        }
    }
}
