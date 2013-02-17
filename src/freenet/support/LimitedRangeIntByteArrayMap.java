package freenet.support;

import java.util.HashMap;

import freenet.io.comm.AsyncMessageCallback;
import freenet.support.Logger.LogLevel;

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

	private static volatile boolean logMINOR;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
    private final HashMap<Integer, LimitedRangeIntByteArrayMapElement> contents;
    private int minValue;
    private int maxValue;
    private final int maxRange;
    /** If this changes, all waiting lock()s must terminate */
    private volatile boolean flag;
    
    public LimitedRangeIntByteArrayMap(int maxRange) {
        this.maxRange = maxRange;
        contents = new HashMap<Integer, LimitedRangeIntByteArrayMapElement>();
        minValue = -1;
        maxValue = -1;
        flag = false;
    }
    
    public synchronized int minValue() {
        return minValue;
    }
    
    public synchronized int maxValue() {
        return maxValue;
    }
    
    public synchronized byte[] get(int index) {
        LimitedRangeIntByteArrayMapElement wrapper = contents.get(index);
        if(wrapper != null)
            return wrapper.data;
        else return null;
    }
    
    public synchronized AsyncMessageCallback[] getCallbacks(int index) {
        LimitedRangeIntByteArrayMapElement wrapper = contents.get(index);
        if(wrapper != null)
            return wrapper.callbacks;
        else return null;
    }
    
    public synchronized long getTime(int index) {
        LimitedRangeIntByteArrayMapElement wrapper = contents.get(index);
        if(wrapper != null)
            return wrapper.createdTime;
        else return -1;
    }
    
	public short getPriority(int index, short defaultValue) {
        Integer i = index;
        LimitedRangeIntByteArrayMapElement wrapper = contents.get(i);
        if(wrapper != null)
            return wrapper.priority;
        else return defaultValue;
	}
	
    /**
     * Get the time at which an index was re-added last.
     */
    public synchronized long getReaddedTime(int index) {
    	LimitedRangeIntByteArrayMapElement wrapper = contents.get(index);
    	if(wrapper != null)
    		return wrapper.reputTime;
    	else return -1;
    }
    
    /**
     * Try to add an index/data mapping.
     * @return True if we succeeded, false if the index was out
     * of range.
     */
    public synchronized boolean add(int index, byte[] data, AsyncMessageCallback[] callbacks, short priority) {
    	if(logMINOR) Logger.minor(this, toString()+" add "+index);
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
        if(data == null) throw new NullPointerException();
        Integer idx = index;
		LimitedRangeIntByteArrayMapElement le = contents.get(idx);
        if(le == null)
        	contents.put(idx, new LimitedRangeIntByteArrayMapElement(idx, data, callbacks, priority));
        else
        	le.reput();
        notifyAll();
        return true;
    }
    
    /**
     * Toggle the flag, and notify all waiting lock()s. They will then throw
     * InterruptedException's.
     */
    public synchronized void interrupt() {
        flag = !flag;
        notifyAll();
    }

    /**
     * Wait until add(index, whatever) would return true.
     * If this returns, add(index, whatever) will work.
     * If it throws, it probably won't.
     */
    public synchronized void lock(int index) throws InterruptedException {
        boolean oldFlag = flag;
        if(minValue == -1) return;
        if(index - minValue < maxRange) return;
        if(logMINOR) Logger.minor(this, toString()+" lock("+index+") - minValue = "+minValue+", maxValue = "+maxValue+", maxRange="+maxRange);
        while(true) {
            wait();
            if(flag != oldFlag) {
            	if(logMINOR) Logger.minor(this, "Interrupted");
            	throw new InterruptedException();
            }
            if((index - minValue < maxRange) || (minValue == -1)) {
            	if(logMINOR) Logger.minor(this, "index="+index+", minValue="+minValue+", maxRange="+maxRange+" - returning");
            	return;
            }
        }
    }
    
    public boolean wouldBlock(int index) {
    	if(minValue == -1) return false;
    	return (index - minValue >= maxRange);
    }
    
    /**
     * Wait until add(index, whatever) would return true.
     * If this returns, add(index, whatever) will work.
     * If it throws, it probably won't.
     */
    public synchronized void lockNeverBlock(int index) throws WouldBlockException {
        if(minValue == -1) return;
        if(index - minValue < maxRange) return;
        throw new WouldBlockException(toString()+ " WOULD BLOCK: lockNeverBlock("+index+") - minValue = "+minValue+", maxValue = "+maxValue+", maxRange="+maxRange);
    }
    
    /**
     * @return true if we removed something.
     */
    public synchronized boolean remove(int index) {
    	if(logMINOR) Logger.minor(this, "Removing "+index+" - min="+minValue+" max="+maxValue);
        if (contents.remove(index) != null) {
            if((index > minValue) && (index < maxValue)) return true;
            if(contents.size() == 0) {
                minValue = maxValue = -1;
                notifyAll();
                return true;
            }
            if(index == maxValue) {
                for(int i=maxValue;i>=minValue;i--) {
                    Integer ii = Integer.valueOf(i);
                    if(contents.containsKey(ii)) {
                        maxValue = i;
                        notifyAll();
                        return true;
                    }
                }
                // Still here - WTF?
                notifyAll();
                throw new IllegalStateException("Still here! (a)");
            }
            if(index == minValue) {
                for(int i=minValue;i<=maxValue;i++) {
                    Integer ii = Integer.valueOf(i);
                    if(contents.containsKey(ii)) {
                        minValue = i;
                        notifyAll();
                        return true;
                    }
                }
                // Still here - WTF?
                notifyAll();
                throw new IllegalStateException("Still here! (b)");
            }
            notifyAll();
            throw new IllegalStateException("impossible");
        }
        return false;
    }

    /**
     * @return The contents of each packet sent, then clear.
     */
    public synchronized byte[][] grabAllBytes() {
        int len = contents.size();
        byte[][] output = new byte[len][];
        int count = 0;
        for (LimitedRangeIntByteArrayMapElement o : contents.values()) {
			output[count++] = o.data;
        }
        clear();
        return output;
    }
    
    public synchronized LimitedRangeIntByteArrayMapElement[] grabAll() {
        int len = contents.size();
        LimitedRangeIntByteArrayMapElement[] output = new LimitedRangeIntByteArrayMapElement[len];
        int count = 0;
		for(LimitedRangeIntByteArrayMapElement e: contents.values()) {
            output[count++] = e;
        }
        clear();
        return output;
    }
    
    /**
     * Empty the structure.
     */
    private synchronized void clear() {
        contents.clear();
        minValue = maxValue = -1;
    }
}
