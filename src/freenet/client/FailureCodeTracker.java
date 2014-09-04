/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import freenet.client.FetchException.FetchExceptionMode;
import freenet.client.InsertException.InsertExceptionMode;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.io.StorageFormatException;

/**
 * Essentially a map of integer to incrementible integer.
 * FIXME maybe move this to support, give it a better name?
 * 
 * WARNING: Changing non-transient members on classes that are Serializable can result in 
 * restarting downloads or losing uploads.
 */
public class FailureCodeTracker implements Cloneable, Serializable {

    private static final long serialVersionUID = 1L;
    public final boolean insert;
	private int total;
	
	public FailureCodeTracker(boolean insert) {
		this.insert = insert;
	}
	
	/**
	 * Create a FailureCodeTracker from a SimpleFieldSet.
	 * @param isInsert Whether this is an insert.
	 * @param fs The SimpleFieldSet containing the FieldSet (non-verbose) form of 
	 * the tracker.
	 */
	public FailureCodeTracker(boolean isInsert, SimpleFieldSet fs) {
		this.insert = isInsert;
		Iterator<String> i = fs.directSubsetNameIterator();
		while(i.hasNext()) {
			String name = i.next();
			SimpleFieldSet f = fs.subset(name);
			// We ignore the Description, if there is one; we just want the count
			int num = Integer.parseInt(name);
			int count = Integer.parseInt(f.get("Count"));
			if(count < 0) throw new IllegalArgumentException("Count < 0");
			map.put(Integer.valueOf(num), count);
			total += count;
		}
	}
	
	protected FailureCodeTracker() {
	    // For serialization.
	    this.insert = false;
	}
	
	private HashMap<Integer, Integer> map;
	
	public void inc(FetchExceptionMode k) {
	    if(insert) throw new IllegalStateException();
	    inc(k.code);
	}

    public void inc(InsertExceptionMode k) {
        if(!insert) throw new IllegalStateException();
        inc(k.code);
    }

	public synchronized void inc(int k) {
		if(k == 0) {
			Logger.error(this, "Can't increment 0, not a valid failure mode", new Exception("error"));
		}
		if(map == null) map = new HashMap<Integer, Integer>();
		Integer key = k;
		Integer i = map.get(key);
		if(i == null)
			map.put(key, 1);
		else
		    map.put(key, i+1);
		total++;
	}

    public void inc(FetchExceptionMode k, int val) {
        if(insert) throw new IllegalStateException();
        inc(k.code, val);
    }

    public void inc(InsertExceptionMode k, int val) {
        if(!insert) throw new IllegalStateException();
        inc(k.code, val);
    }

	public synchronized void inc(Integer k, int val) {
		if(k == 0) {
			Logger.error(this, "Can't increment 0, not a valid failure mode", new Exception("error"));
		}
		if(map == null) map = new HashMap<Integer, Integer>();
		Integer key = k;
		Integer i = map.get(key);
		if(i == null)
			map.put(key, 1);
		else
		    map.put(key, i+val);
		total += val;
	}
	
	public synchronized String toVerboseString() {
		if(map == null) return super.toString()+":empty";
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<Integer, Integer> e : map.entrySet()) {
			Integer x = e.getKey();
			Integer val = e.getValue();
			String s = getMessage(x);
			sb.append(val);
			sb.append('\t');
			sb.append(s);
			sb.append('\n');
		}
		return sb.toString();
	}

	public String getMessage(Integer x) {
	    return insert ? InsertException.getMessage(InsertExceptionMode.getByCode(x)) : 
	        FetchException.getMessage(FetchExceptionMode.getByCode(x));
    }

    @Override
	public synchronized String toString() {
		if(map == null) return super.toString()+":empty";
		StringBuilder sb = new StringBuilder(super.toString());
		sb.append(':');
		if(map.size() == 0) sb.append("empty");
		else if(map.size() == 1) {
			sb.append("one:");
			Integer code = (Integer) (map.keySet().toArray())[0];
			sb.append(code);
			sb.append('=');
			sb.append((map.get(code)));
		} else if(map.size() < 10) {
			boolean needComma = false;
			for(Map.Entry<Integer, Integer> entry : map.entrySet()) {
				if(needComma)
					sb.append(',');
				sb.append(entry.getKey()); // code
				sb.append('=');
				sb.append(entry.getValue());
				needComma = true;
			}
		} else {
			sb.append(map.size());
		}
		return sb.toString();
	}
	
	/**
	 * Merge codes from another tracker into this one.
	 */
	public synchronized FailureCodeTracker merge(FailureCodeTracker source) {
		if(source.map == null) return this;
		if(map == null) map = new HashMap<Integer, Integer>();
		for (Map.Entry<Integer, Integer> e : source.map.entrySet()) {
			Integer k = e.getKey();
			Integer item = e.getValue();
			inc(k, item);
		}
		return this;
	}

	public void merge(FetchException e) {
		if(insert) throw new IllegalStateException("Merging a FetchException in an insert!");
		if(e.errorCodes != null) {
			merge(e.errorCodes);
		}
		// Increment mode anyway, so we get the splitfile error as well.
		inc(e.mode.code);
	}

	public synchronized int totalCount() {
		return total;
	}

	/** Copy verbosely to a SimpleFieldSet */
	public synchronized SimpleFieldSet toFieldSet(boolean verbose) {
		SimpleFieldSet sfs = new SimpleFieldSet(false);
		if(map != null) {
		for (Map.Entry<Integer, Integer> e : map.entrySet()) {
			Integer k = e.getKey();
			Integer item = e.getValue();
			int code = k.intValue();
			// prefix.num.Description=<code description>
			// prefix.num.Count=<count>
			if(verbose)
				sfs.putSingle(Integer.toString(code)+".Description", getMessage(code));
			sfs.put(Integer.toString(code)+".Count", item);
		}
		}
		return sfs;
	}

	public synchronized boolean isOneCodeOnly() {
	    if(map == null) return true;
		return map.size() == 1;
	}
	
    public FetchExceptionMode getFirstCodeFetch() {
        if(insert) throw new IllegalStateException();
        return FetchExceptionMode.getByCode(getFirstCode());
    }

    public InsertExceptionMode getFirstCodeInsert() {
        if(!insert) throw new IllegalStateException();
        return InsertExceptionMode.getByCode(getFirstCode());
    }

	public synchronized int getFirstCode() {
		return ((Integer) map.keySet().toArray()[0]).intValue();
	}

	public synchronized boolean isFatal(boolean insert) {
		if(map == null) return false;
		for (Map.Entry<Integer, Integer> e : map.entrySet()) {
			Integer code = e.getKey();
			if(e.getValue() == 0) continue;
			if(insert) {
				if(InsertException.isFatal(InsertExceptionMode.getByCode(code))) return true;
			} else {
				if(FetchException.isFatal(FetchExceptionMode.getByCode(code))) return true;
			}
		}
		return false;
	}

	public void merge(InsertException e) {
		if(!insert) throw new IllegalArgumentException("This is not an insert yet merge("+e+") called!");
		if(e.errorCodes != null)
			merge(e.errorCodes);
		inc(e.getMode());
	}

	public synchronized boolean isEmpty() {
		return map == null || map.isEmpty();
	}

	/** Copy the FailureCodeTracker. We implement Cloneable to shut up findbugs, but Object.clone() won't
	 * work because it's a shallow copy, so we implement it with merge(). */
	@Override
	public FailureCodeTracker clone() {
		FailureCodeTracker tracker = new FailureCodeTracker(insert);
		tracker.merge(this);
		return tracker;
	}

	public synchronized boolean isDataFound() {
	    if(!insert) throw new IllegalStateException();
		for(Map.Entry<Integer, Integer> entry : map.entrySet()) {
			if(entry.getValue() <= 0) continue;
			if(FetchException.isDataFound(FetchExceptionMode.getByCode(entry.getKey()), null)) return true;
		}
		return false;
	}
	
	private int MAGIC = 0xb605aa08;
	private int VERSION = 1;
	
	/** Get the length of the fixed-size representation produced by writeFixedLengthTo(). */
	public static int getFixedLength(boolean insert) {
        int upperLimit = 
            insert ? InsertException.UPPER_LIMIT_ERROR_CODE : FetchException.UPPER_LIMIT_ERROR_CODE;
        return 4 + 4 + 4 + 4 * upperLimit;
	}
	
	/** Write a fixed-size representation to a DataOutputStream. This is important for e.g. 
	 * splitfiles, where we have a fixed part of the disk file to save it to. */
	public synchronized void writeFixedLengthTo(DataOutputStream dos) throws IOException {
	    int upperLimit = 
	        insert ? InsertException.UPPER_LIMIT_ERROR_CODE : FetchException.UPPER_LIMIT_ERROR_CODE;
	    dos.writeInt(MAGIC);
	    dos.writeInt(VERSION);
	    dos.writeInt(upperLimit);
	    for(int i=0;i<upperLimit;i++)
	        dos.writeInt(getErrorCount(i));
	}

	/** Get number of errors of count mode */
    public synchronized int getErrorCount(int mode) {
        if(map == null) return 0;
        Integer item = map.get(mode);
        return item == null ? 0 : item;
    }
    
    /** Get number of errors of count mode */
    public synchronized int getErrorCount(InsertExceptionMode mode) {
        if(!insert) throw new IllegalStateException();
        return getErrorCount(mode.code);
    }
    
    /** Get number of errors of count mode */
    public synchronized int getErrorCount(FetchExceptionMode mode) {
        if(insert) throw new IllegalStateException();
        return getErrorCount(mode.code);
    }
    
    public FailureCodeTracker(boolean insert, DataInputStream dis) throws IOException, StorageFormatException {
        this.insert = insert;
        if(dis.readInt() != MAGIC) 
            throw new StorageFormatException("Bad magic for FailureCodeTracker");
        if(dis.readInt() != VERSION)
            throw new StorageFormatException("Bad version for FailureCodeTracker");
        int upperLimit = 
            insert ? InsertException.UPPER_LIMIT_ERROR_CODE : FetchException.UPPER_LIMIT_ERROR_CODE;
        if(dis.readInt() != upperLimit)
            throw new StorageFormatException("Bad upper limit for FailureCodeTracker");
        for(int i=0;i<upperLimit;i++) {
            int x = dis.readInt();
            if(x < 0) throw new StorageFormatException("Negative error counts");
            if(x == 0) continue;
            if(map == null) map = new HashMap<Integer, Integer>();
            total += x;
            map.put(i, x);
        }
    }
}
