package freenet.support;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import freenet.support.io.LineReader;

/**
 * @author amphibian
 * 
 * Very very simple FieldSet type thing, which uses the standard
 * Java facilities.
 */
public class SimpleFieldSet {

    private final Map values;
    private Map subsets;
    private String endMarker;
    static public final char MULTI_LEVEL_CHAR = '.';
    
    public SimpleFieldSet(BufferedReader br) throws IOException {
        values = new HashMap();
       	subsets = null;
        read(br);
    }
    
    public SimpleFieldSet(SimpleFieldSet sfs){
    	values = new HashMap(sfs.values);
    	subsets = new HashMap(sfs.subsets);
    	endMarker = sfs.endMarker;
    }

    public SimpleFieldSet(LineReader lis, int maxLineLength, int lineBufferSize, boolean tolerant, boolean utf8OrIso88591) throws IOException {
    	values = new HashMap();
       	subsets = null;
    	read(lis, maxLineLength, lineBufferSize, tolerant, utf8OrIso88591);
    }
    
    /**
     * Empty constructor
     */
    public SimpleFieldSet() {
        values = new HashMap();
       	subsets = null;
    }

    /**
     * Construct from a string.
     * @throws IOException if the string is too short or invalid.
     */
    public SimpleFieldSet(String content, boolean multiLevel) throws IOException {
    	values = new HashMap();
    	subsets = null;
        StringReader sr = new StringReader(content);
        BufferedReader br = new BufferedReader(sr);
	    read(br);
    }
    
    /**
     * Read from disk
     * Format:
     * blah=blah
     * blah=blah
     * End
     */
    private void read(BufferedReader br) throws IOException {
        boolean firstLine = true;
        while(true) {
            String line = br.readLine();
            if(line == null) {
                if(firstLine) throw new EOFException();
                throw new IOException();
            }
            firstLine = false;
            int index = line.indexOf('=');
            if(index >= 0) {
                // Mapping
                String before = line.substring(0, index);
                String after = line.substring(index+1);
                put(before, after);
            } else {
            	endMarker = line;
            	return;
            }
            
        }
    }

    /**
     * Read from disk
     * Format:
     * blah=blah
     * blah=blah
     * End
     * @param utfOrIso88591 If true, read as UTF-8, otherwise read as ISO-8859-1.
     */
    private void read(LineReader br, int maxLength, int bufferSize, boolean tolerant, boolean utfOrIso88591) throws IOException {
        boolean firstLine = true;
        while(true) {
            String line = br.readLine(maxLength, bufferSize, utfOrIso88591);
            if(line == null) {
                if(firstLine) throw new EOFException();
                if(tolerant)
                	Logger.error(this, "No end marker");
                else
                	throw new IOException("No end marker");
                return;
            }
            if((line.length() == 0) && tolerant) continue; // ignore
            firstLine = false;
            int index = line.indexOf('=');
            if(index >= 0) {
                // Mapping
                String before = line.substring(0, index);
                String after = line.substring(index+1);
                put(before, after);
            } else {
            	endMarker = line;
            	return;
            }
            
        }
    }
    
    public synchronized String get(String key) {
   		int idx = key.indexOf(MULTI_LEVEL_CHAR);
   		if(idx == -1)
   			return (String) values.get(key);
   		else if(idx == 0)
   			return null;
   		else {
   			if(subsets == null) return null;
   			String before = key.substring(0, idx);
   			String after = key.substring(idx+1);
   			SimpleFieldSet fs = (SimpleFieldSet) (subsets.get(before));
   			if(fs == null) return null;
   			return fs.get(after);
   		}
    }
    
    public String[] getAll(String key) {
    	return split(get(key));
    }

    private static final String[] split(String string) {
    	if(string == null) return new String[0];
    	return string.split(";"); // slower???
//    	int index = string.indexOf(';');
//    	if(index == -1) return null;
//    	Vector v=new Vector();
//    	v.removeAllElements();
//        while(index>0){
//            // Mapping
//            String before = string.substring(0, index);         
//            String after = string.substring(index+1);
//            v.addElement(before);
//            string=after;
//            index = string.indexOf(';');
//        }
//    	
//    	return (String[]) v.toArray();
	}

	public synchronized void put(String key, String value) {
		int idx;
		if(value == null) return;
		if((idx = key.indexOf(MULTI_LEVEL_CHAR)) == -1) {
			String x = (String) values.get(key);
			
			if(x == null) {
				values.put(key, value);
			} else {
				values.put(key, ((String)values.get(key))+";"+value);
			}
		} else {
			String before = key.substring(0, idx);
			String after = key.substring(idx+1);
			SimpleFieldSet fs = null;
			if(subsets == null)
				subsets = new HashMap();
			fs = (SimpleFieldSet) (subsets.get(before));
			if(fs == null) {
				fs = new SimpleFieldSet();
				subsets.put(before, fs);
			}
			fs.put(after, value);
		}
    }

	public void put(String key, int value) {
		put(key, Integer.toString(value));
	}
	
	public void put(String key, long value) {
		put(key, Long.toString(value));
	}
	
	public void put(String key, short value) {
		put(key, Short.toString(value));
	}
	
	public void put(String key, char c) {
		put(key, ""+c);
	}
	
	public void put(String key, boolean b) {
		put(key, Boolean.toString(b));
	}
	
	public void put(String key, double windowSize) {
		put(key, Double.toString(windowSize));
	}

    /**
     * Write the contents of the SimpleFieldSet to a Writer.
     * @param osr
     */
	public void writeTo(Writer w) throws IOException {
		writeTo(w, "", false);
	}
	
    synchronized void writeTo(Writer w, String prefix, boolean noEndMarker) throws IOException {
    	for(Iterator i = values.entrySet().iterator();i.hasNext();) {
            Map.Entry entry = (Map.Entry) i.next();
            String key = (String) entry.getKey();
            String value = (String) entry.getValue();
            w.write(prefix+key+"="+value+"\n");
    	}
    	if(subsets != null) {
    		for(Iterator i = subsets.entrySet().iterator();i.hasNext();) {
    			Map.Entry entry = (Map.Entry) i.next();
    			String key = (String) entry.getKey();
    			SimpleFieldSet subset = (SimpleFieldSet) entry.getValue();
    			if(subset == null) throw new NullPointerException();
    			subset.writeTo(w, prefix+key+MULTI_LEVEL_CHAR, true);
    		}
    	}
    	if(!noEndMarker) {
    		if(endMarker == null)
    			w.write("End\n");
    		else
    			w.write(endMarker+"\n");
    	}
    }
    
    public void writeToOrdered(Writer w) throws IOException {
		writeToOrdered(w, "", false);
	}
    
    synchronized void writeToOrdered(Writer w, String prefix, boolean noEndMarker) throws IOException {
    	String[] keys = (String[]) values.keySet().toArray();
    	int i=0;
    
    	// Sort
    	Arrays.sort(keys);
    	
    	// Output
    	for(i=0; i < keys.length; i++)
    		w.write(prefix+keys[i]+'='+get(keys[i])+'\n');
    	
    	if(subsets != null) {
    		String[] orderedPrefixes = (String[]) subsets.keySet().toArray();
    		// Sort
    		Arrays.sort(orderedPrefixes);
    		
        	for(i=0; i < orderedPrefixes.length; i++) {
    			SimpleFieldSet subset = subset(orderedPrefixes[i]);
    			if(subset == null) throw new NullPointerException();
    			subset.writeToOrdered(w, prefix+orderedPrefixes[i]+MULTI_LEVEL_CHAR, true);
    		}
    	}
    
    	if(!noEndMarker) {
    		if(endMarker == null)
    			w.write("End\n");
    		else
    			w.write(endMarker+"\n");
    	}
    }
    
    public String toString() {
        StringWriter sw = new StringWriter();
        try {
            writeTo(sw);
        } catch (IOException e) {
            Logger.error(this, "WTF?!: "+e+" in toString()!", e);
        }
        return sw.toString();
    }
    
    public String toOrderedString() {
    	StringWriter sw = new StringWriter();
        try {
            writeToOrdered(sw);
        } catch (IOException e) {
            Logger.error(this, "WTF?!: "+e+" in toString()!", e);
        }
        return sw.toString();
    }
    
    public String getEndMarker() {
    	return endMarker;
    }
    
    public void setEndMarker(String s) {
    	endMarker = s;
    }

	public synchronized SimpleFieldSet subset(String key) {
		if(subsets == null) return null;
		int idx = key.indexOf(MULTI_LEVEL_CHAR);
		if(idx == -1)
			return (SimpleFieldSet) subsets.get(key);
		String before = key.substring(0, idx);
		String after = key.substring(idx+1);
		SimpleFieldSet fs = (SimpleFieldSet) subsets.get(before);
		if(fs == null) return null;
		return fs.subset(after);
	}

	public Iterator keyIterator() {
		return new KeyIterator("");
	}

	KeyIterator keyIterator(String prefix) {
		return new KeyIterator(prefix);
	}
	
    public class KeyIterator implements Iterator {
    	
    	final Iterator valuesIterator;
    	final Iterator subsetIterator;
    	KeyIterator subIterator;
    	String prefix;
    	
    	public KeyIterator(String prefix) {
    		valuesIterator = values.keySet().iterator();
    		if(subsets != null)
    			subsetIterator = subsets.keySet().iterator();
    		else
    			subsetIterator = null;
    		this.prefix = prefix;
    	}

		public boolean hasNext() {
			synchronized(SimpleFieldSet.this) {
				if(valuesIterator.hasNext()) return true;
				if((subIterator != null) && subIterator.hasNext()) return true;
				if(subIterator != null) subIterator = null;
				return false;
			}
		}

		public final Object next() {
			return nextKey();
		}
		
		public String nextKey() {
			synchronized(SimpleFieldSet.this) {
				String ret = null;
				if(ret == null && valuesIterator.hasNext()) {
					return prefix + valuesIterator.next();
				}
				while(true) {
					// Iterate subsets.
					if(subIterator != null && subIterator.hasNext()) {
						if(ret != null)
							// Found next but one, can return next
							return ret;
						ret = (String) subIterator.next();
						if(subIterator.hasNext()) {
							if(ret != null) return ret;
						} else {
							subIterator = null;
						}
					} else
						subIterator = null;
					if(subsetIterator != null && subsetIterator.hasNext()) {
						String key = (String) subsetIterator.next();
						SimpleFieldSet fs = (SimpleFieldSet) subsets.get(key);
						String newPrefix = prefix + key + MULTI_LEVEL_CHAR;
						subIterator = fs.keyIterator(newPrefix);
					}
				}
			}
		}

		public synchronized void remove() {
			throw new UnsupportedOperationException();
		}
	}

    /** Tolerant put(); does nothing if fs is empty */
    public void tput(String key, SimpleFieldSet fs) {
    	if(fs == null || fs.isEmpty()) return;
    	put(key, fs);
    }
    
	public void put(String key, SimpleFieldSet fs) {
		if(fs == null) return; // legal no-op, because used everywhere
		if(fs.isEmpty()) // can't just no-op, because caller might add the FS then populate it...
			throw new IllegalArgumentException("Empty");
		if(subsets == null)
			subsets = new HashMap();
		if(subsets.containsKey(key))
			throw new IllegalArgumentException("Already contains "+key+" but trying to add a SimpleFieldSet!");
		subsets.put(key, fs);
	}

	public synchronized void removeValue(String key) {
		int idx;
		if((idx = key.indexOf(MULTI_LEVEL_CHAR)) == -1) {
			values.remove(key);
		} else {
			if(subsets == null) return;
			String before = key.substring(0, idx);
			String after = key.substring(idx+1);
			SimpleFieldSet fs = (SimpleFieldSet) (subsets.get(before));
			if(fs == null) {
				return;
			}
			fs.removeValue(after);
			if(fs.isEmpty()) {
				subsets.remove(before);
				if(subsets.isEmpty())
					subsets = null;
			}
		}
	}

	public synchronized void removeSubset(String key) {
		if(subsets == null) return;
		int idx;
		if((idx = key.indexOf(MULTI_LEVEL_CHAR)) == -1) {
			subsets.remove(key);
		} else {
			String before = key.substring(0, idx);
			String after = key.substring(idx+1);
			SimpleFieldSet fs = (SimpleFieldSet) (subsets.get(before));
			if(fs == null) {
				return;
			}
			fs.removeSubset(after);
			if(fs.isEmpty()) {
				subsets.remove(before);
				if(subsets.isEmpty())
					subsets = null;
			}
		}
	}
	
	/** Is this SimpleFieldSet empty? */
	public boolean isEmpty() {
		return values.isEmpty() && (subsets == null || subsets.isEmpty());
	}

	public Iterator directSubsetNameIterator() {
		return subsets.keySet().iterator();
	}

	public String[] namesOfDirectSubsets() {
		return (String[]) subsets.keySet().toArray(new String[subsets.size()]);
	}

	public static SimpleFieldSet readFrom(File f) throws IOException {
		FileInputStream fis = null;
		try {
			fis = new FileInputStream(f);
			BufferedInputStream bis = new BufferedInputStream(fis);
			InputStreamReader isr;
			try {
				isr = new InputStreamReader(bis, "UTF-8");
			} catch (UnsupportedEncodingException e) {
				Logger.error(SimpleFieldSet.class, "Impossible: "+e, e);
				fis.close();
				return null;
			}
			BufferedReader br = new BufferedReader(isr);
			SimpleFieldSet fs = new SimpleFieldSet(br);
			br.close();
			fis = null;
			return fs;
		} finally {
			try {
				if(fis != null) fis.close();
			} catch (IOException e) {
				// Ignore
			}
		}
	}

	public long getInt(String key, int def) {
		String s = get(key);
		if(s == null) return def;
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return def;
		}
	}

	public double getDouble(String key, double def) {
		String s = get(key);
		if(s == null) return def;
		try {
			return Double.parseDouble(s);
		} catch (NumberFormatException e) {
			return def;
		}
	}

	public long getLong(String key, long def) {
		String s = get(key);
		if(s == null) return def;
		try {
			return Long.parseLong(s);
		} catch (NumberFormatException e) {
			return def;
		}
	}

	public boolean getBoolean(String key, boolean def) {
		return Fields.stringToBool(get(key), def);
	}

}
