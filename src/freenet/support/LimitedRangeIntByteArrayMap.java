package freenet.support;

import java.util.HashMap;

/**
 * @author amphibian
 * 
 * A list of integers linked to byte[]'s.
 * We keep a minimum and maximum of the integers.
 * The integers are expected to be reasonably close together, and
 * there is a maximum range. We provide a mechanism to sleep 
 * until the given packet number will no longer exceed the 
 * maximum range (which can be interrupted).
 */
public class LimitedRangeIntByteArrayMap {

    private final HashMap contents;
    private int minValue;
    private int maxValue;
    private final int maxRange;
    
    public LimitedRangeIntByteArrayMap(int maxRange) {
        this.maxRange = maxRange;
        contents = new HashMap();
        minValue = -1;
        maxValue = -1;
    }
    
    public int minValue() {
        return minValue;
    }
    
    public int maxValue() {
        return maxValue;
    }
    
    public synchronized byte[] get(int index) {
        Integer i = new Integer(index);
        byte[] data = (byte[]) contents.get(i);
        return data;
    }
    
    /**
     * Try to add an index/data mapping.
     * @return True if we succeeded, false if the index was out
     * of range.
     */
    public synchronized boolean add(int index, byte[] data) {
        if(maxValue == -1) {
            minValue = index;
            maxValue = index;
        }
        if(index > maxValue) {
            if(index-minValue >= maxRange)
                return false;
            maxValue = index;
        }
        if(index < minValue) {
            if(maxValue-index >= maxRange)
                return false;
            minValue = index;
        }
        contents.put(new Integer(index), data);
        notifyAll();
        return true;
    }

    /**
     * Wait until add(index, whatever) would return true.
     * If this returns, add(index, whatever) will work.
     * If it throws, it probably won't.
     */
    public synchronized void lock(int index) throws InterruptedException {
        if(index - minValue < maxRange) return;
        while(true) {
            wait();
            if(index - minValue < maxRange) return;
        }
    }
    
    /**
     * Wait until add(index, whatever) would return true.
     * If this returns, add(index, whatever) will work.
     * If it throws, it probably won't.
     */
    public synchronized void lockNeverBlock(int index) throws WouldBlockException {
        if(index - minValue < maxRange) return;
        throw new WouldBlockException();
    }
    
    public synchronized void remove(int index) {
        if(contents.remove(new Integer(index)) != null) {
            if(index > minValue && index < maxValue) return;
            if(contents.size() == 0) {
                minValue = maxValue = -1;
                return;
            }
            if(index == maxValue) {
                for(int i=maxValue;i>minValue;i--) {
                    Integer ii = new Integer(i);
                    if(contents.containsKey(ii)) {
                        maxValue = i;
                        return;
                    }
                }
                // Still here - WTF?
                throw new IllegalStateException("Still here! (a)");
            }
            if(index == minValue) {
                for(int i=minValue;i<maxValue;i++) {
                    Integer ii = new Integer(i);
                    if(contents.containsKey(ii)) {
                        minValue = i;
                        return;
                    }
                }
                // Still here - WTF?
                throw new IllegalStateException("Still here! (b)");
            }
            throw new IllegalStateException("impossible");
        }
    }
}
