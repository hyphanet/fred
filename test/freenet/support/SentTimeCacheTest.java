/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

import junit.framework.TestCase;

/**
 * Tests for {@link SentTimeCache}.
 *
 * @author bertm
 */
public class SentTimeCacheTest extends TestCase {
    private static final int CACHE_SIZE = 32;

    /**
     * Tests if the cache adheres to its given maximum capacity.
     */
    public void testMaxSize() {
        SentTimeCache c = newCache();
        fillWithSequence(c);
        c.sent(CACHE_SIZE + 1);
        assertEquals(c.size(), CACHE_SIZE);
    }

    /**
     * Tests the correctness of simple {@link SentTimeCache#queryAndRemove(int)} use.
     */
    public void testQueryAndRemove() {
        SentTimeCache c = newCache();
        fillWithSequence(c);

        // Test if all entries are reported back correctly.
        for (int n = 1; n <= CACHE_SIZE; n++) {
            long t = c.queryAndRemove(n);
            // The returned time is correct.
            assertEquals(t, n);
            // The entry is removed from the cache.
            assertEquals(c.size(), CACHE_SIZE - n);
        }
    }

    /**
     * Tests the FIFO characteristics of the cache.
     */
    public void testFifo() {
        SentTimeCache c = newCache();
        fillWithSequence(c);
        // Test if old entries are pushed out.
        for (int n = 1; n <= CACHE_SIZE; n++) {
            // Push the oldest entry out by reporting a new one.
            c.report(n + CACHE_SIZE, n + CACHE_SIZE);
            // Try to fetch the entry that should have been removed.
            long t = c.queryAndRemove(n);
            // The query was not successful.
            assertTrue(t < 0);
            // Nothing was removed from from the cache.
            assertEquals(c.size(), CACHE_SIZE);
        }
        // Test if the newly inserted entries are kept.
        for (int n = 1; n <= CACHE_SIZE; n++) {
            long t = c.queryAndRemove(n + CACHE_SIZE);
            assertEquals(t, n + CACHE_SIZE);
        }
    }

    /**
     * Constructs a new cache of size {@link #CACHE_SIZE} and asserts its emptiness.
     * @return The cache.
     */
    private SentTimeCache newCache() {
        SentTimeCache c = new SentTimeCache(CACHE_SIZE);
        assertEquals(c.size(), 0);
        return c;
    }

    /**
     * Fills the cache with sequence [1..{@link #CACHE_SIZE}] ({@code seqnum} equal to {@code
     * time}), asserting it is at full capacity before returning.
     * @param c the cache
     */
    private void fillWithSequence(SentTimeCache c) {
        for (int n = 1; n <= CACHE_SIZE; n++) {
            c.report(n, n);
        }
        assertEquals(c.size(), CACHE_SIZE);
    }
}
