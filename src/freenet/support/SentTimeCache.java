/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps track of times at which sequence numbers were reported as sent, with bounded capacity.
 * Cached times at which {@link #sent(int)} was invoked are dropped on a first-in first-out basis.
 *
 * @author bertm
 */
public class SentTimeCache {

    /**
     * LinkedHashMap with int keys, long values, that has bounded capacity.
     */
    private static class BoundedSentTimeMap extends LinkedHashMap<Integer, Long> {
        private static final long serialVersionUID = 0;
        private final int maxSize;
        
        /**
         * Constructs a map with the given maximum (and initial) size.
         */
        BoundedSentTimeMap(int maxSize) {
            super(maxSize);
            if (maxSize <= 0) {
                throw new IllegalArgumentException("Negative or zero maxSize");
            }
            this.maxSize = maxSize;
        }
        
        /**
         * Automatically maintains the maximum size by returning true if the capacity is exceeded,
         * indicating that the eldest entry must be removed.
         * @see LinkedHashMap#removeEldestEntry(Map.Entry)
         */
        protected boolean removeEldestEntry(Map.Entry<Integer, Long> eldest) {
            return size() > maxSize;
        }
    }
    
    /**
     * The inner cache.
     */
    private BoundedSentTimeMap cache;
    
    /**
     * Constructs a sent time cache with the given maximal capacity.
     */
    public SentTimeCache(int maxSize) {
        cache = new BoundedSentTimeMap(maxSize);
    }
    
    /**
     * Reports the given sequence number as being sent at the given time. If the cache is at full
     * capacity, this will lead to the oldest entry being dropped. If the sequence number was
     * already in this cache, the given time will be associated with the sequence number, but the 
     * order in which sequence numbers are dropped from the cache will not be affected.
     * @param seqnum the sequence number
     * @param time the sent time in milliseconds
     */
    public synchronized void report(int seqnum, long time) {
        cache.put(seqnum, time);
    }
    
    /**
     * Convenience wrapper for {@link #report(int, long)}.
     * Reports the given sequence number as being sent right now. If the cache is at full capacity,
     * this will lead to the oldest entry being dropped. If the sequence number was already in this
     * cache, the current time will be associated with the sequence number, but the order in which
     * sequence numbers are dropped from the cache will not be affected.
     * @param seqnum the sequence number
     * @see SentTimeCache#report(int, long)
     */
    public synchronized void sent(int seqnum) {
        long time = System.currentTimeMillis();
        report(seqnum, time);
    }
    
    /**
     * Queries the sent time for the given sequence number and removes it from the cache.
     * @param seqnum the sequence number
     * @returns The time at which the sequence number was reported to {@link #sent(int)}, in
     * milliseconds, or a negative value if the sequence number was not in this cache.
     */
    public synchronized long queryAndRemove(int seqnum) {
        Long ret = cache.remove(seqnum);
        if (ret == null) {
            return -1;
        }
        return ret;
    }

    /**
     * Queries the number of items currently held by this cache.
     * @return The number of items in this cache.
     */
    synchronized int size() {
        return cache.size();
    }
}

