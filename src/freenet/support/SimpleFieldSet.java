package freenet.support;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import freenet.node.FSParseException;
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
    private final boolean shortLived;
    static public final char MULTI_LEVEL_CHAR = '.';
    
    /**
     * Construct a SimpleFieldSet from reading a BufferedReader.
     * @param br
     * @param allowMultiple If true, multiple lines with the same field name will be
     * combined; if false, the constructor will throw.
     * @param shortLived If false, strings will be interned to ensure that they use as
     * little memory as possible. Only set to true if the SFS will be short-lived or
     * small.
     * @throws IOException If the buffer could not be read, or if there was a formatting
     * problem.
     */
    public SimpleFieldSet(BufferedReader br, boolean allowMultiple, boolean shortLived) throws IOException {
        values = new HashMap();
       	subsets = null;
       	this.shortLived = shortLived;
        read(br, allowMultiple);
    }

    public SimpleFieldSet(SimpleFieldSet sfs){
    	values = new HashMap(sfs.values);
    	subsets = new HashMap(sfs.subsets);
    	this.shortLived = false; // it's been copied!
    	endMarker = sfs.endMarker;
    }

    public SimpleFieldSet(LineReader lis, int maxLineLength, int lineBufferSize, boolean tolerant, boolean utf8OrIso88591, boolean allowMultiple, boolean shortLived) throws IOException {
    	values = new HashMap();
       	subsets = null;
       	this.shortLived = shortLived;
    	read(lis, maxLineLength, lineBufferSize, tolerant, utf8OrIso88591, allowMultiple);
    }
    
    /**
     * Create a SimpleFieldSet.
     * @param shortLived If false, strings will be interned to ensure that they use as
     * little memory as possible. Only set to true if the SFS will be short-lived or
     * small.
     */
    public SimpleFieldSet(boolean shortLived) {
        values = new HashMap();
       	subsets = null;
       	this.shortLived = shortLived;
    }

    /**
     * Construct from a string.
     * @param shortLived If false, strings will be interned to ensure that they use as
     * little memory as possible. Only set to true if the SFS will be short-lived or
     * small.
     * @throws IOException if the string is too short or invalid.
     */
    public SimpleFieldSet(String content, boolean allowMultiple, boolean shortLived) throws IOException {
    	values = new HashMap();
    	subsets = null;
    	this.shortLived = shortLived;
        StringReader sr = new StringReader(content);
        BufferedReader br = new BufferedReader(sr);
	    read(br, allowMultiple);
    }
    
    /**
     * Read from disk
     * Format:
     * blah=blah
     * blah=blah
     * End
     * @param allowMultiple 
     */
    private void read(BufferedReader br, boolean allowMultiple) throws IOException {
        boolean firstLine = true;
        while(true) {
            String line = br.readLine();
            if(line == null) {
                if(firstLine) throw new EOFException();
                throw new IOException(); // No end marker!
            }
            firstLine = false;
            int index = line.indexOf('=');
            if(index >= 0) {
                // Mapping
                String before = line.substring(0, index);
                String after = line.substring(index+1);
                if(!shortLived) after = after.intern();
                put(before, after, allowMultiple, false);
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
    private void read(LineReader br, int maxLength, int bufferSize, boolean tolerant, boolean utfOrIso88591, boolean allowMultiple) throws IOException {
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
                if(!shortLived) after = after.intern();
                put(before, after, allowMultiple, false);
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

    /**
     * Set a key to a value. If the value already exists, throw IllegalStateException.
     * @param key The key.
     * @param value The value.
     */
    public void putSingle(String key, String value) {
    	if(value == null) return;
    	if(!shortLived) value = value.intern();
    	if(!put(key, value, false, false))
    		throw new IllegalStateException("Value already exists: "+value+" but want to set "+key+" to "+value);
    }
    
    /**
     * Aggregating put. Set a key to a value, if the value already exists, append to it. 
     * @param key The key.
     * @param value The value.
     */
    public void putAppend(String key, String value) {
    	if(value == null) return;
    	if(!shortLived) value = value.intern();
    	put(key, value, true, false);
    }
    
    /**
     * Set a key to a value, overwriting any existing value if present.
     * @param key The key.
     * @param value The value.
     */
    public void putOverwrite(String key, String value) {
    	if(value == null) return;
    	if(!shortLived) value = value.intern();
    	put(key, value, false, true);
    }
    
    /**
     * Set a key to a value.
     * @param key The key.
     * @param value The value.
     * @param allowMultiple If true, if the key already exists then the value will be
     * appended to the existing value. If false, we return false to indicate that the
     * old value is unchanged.
     * @return True unless allowMultiple was false and there was a pre-existing value,
     * or value was null.
     */
	private synchronized final boolean put(String key, String value, boolean allowMultiple, boolean overwrite) {
		int idx;
		if(value == null) return true; // valid no-op
		if((idx = key.indexOf(MULTI_LEVEL_CHAR)) == -1) {
			String x = (String) values.get(key);
			
			if(!shortLived) key = key.intern();
			if(x == null || overwrite) {
				values.put(key, value);
			} else {
				if(!allowMultiple) return false;
				values.put(key, ((String)values.get(key))+ ';' +value);
			}
		} else {
			String before = key.substring(0, idx);
			String after = key.substring(idx+1);
			SimpleFieldSet fs = null;
			if(subsets == null)
				subsets = new HashMap();
			fs = (SimpleFieldSet) (subsets.get(before));
			if(fs == null) {
				fs = new SimpleFieldSet(shortLived);
				if(!shortLived) before = before.intern();
				subsets.put(before, fs);
			}
			fs.put(after, value, allowMultiple, overwrite);
		}
		return true;
    }

	public void put(String key, int value) {
		// Use putSingle so it does the intern check
		putSingle(key, Integer.toString(value));
	}
	
	public void put(String key, long value) {
		putSingle(key, Long.toString(value));
	}
	
	public void put(String key, short value) {
		putSingle(key, Short.toString(value));
	}
	
	public void put(String key, char c) {
		putSingle(key, Character.toString(c));
	}
	
	public void put(String key, boolean b) {
		// Don't use putSingle, avoid intern check (Boolean.toString returns interned strings anyway)
		put(key, Boolean.toString(b), false, false);
	}
	
	public void put(String key, double windowSize) {
		putSingle(key, Double.toString(windowSize));
	}

    /**
     * Write the contents of the SimpleFieldSet to a Writer.
     * Note: The caller *must* buffer the writer to avoid lousy performance!
     * (StringWriter is by definition buffered, otherwise wrap it in a BufferedWriter)
     */
	public void writeTo(Writer w) throws IOException {
		writeTo(w, "", false);
	}
	
    /**
     * Write the contents of the SimpleFieldSet to a Writer.
     * Note: The caller *must* buffer the writer to avoid lousy performance!
     * (StringWriter is by definition buffered, otherwise wrap it in a BufferedWriter)
     */
    synchronized void writeTo(Writer w, String prefix, boolean noEndMarker) throws IOException {
    	for(Iterator i = values.entrySet().iterator();i.hasNext();) {
            Map.Entry entry = (Map.Entry) i.next();
            String key = (String) entry.getKey();
            String value = (String) entry.getValue();
            w.write(prefix);
            w.write(key);
            w.write('=');
            w.write(value);
            w.write('\n');
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
    		else {
    			w.write(endMarker);
    			w.write('\n');
    		}
    	}
    }
    
    public void writeToOrdered(Writer w) throws IOException {
		writeToOrdered(w, "", false);
	}
    
    private synchronized void writeToOrdered(Writer w, String prefix, boolean noEndMarker) throws IOException {
    	String[] keys = (String[]) values.keySet().toArray(new String[values.size()]);
    	int i=0;
    
    	// Sort
    	Arrays.sort(keys);
    	
    	// Output
    	for(i=0; i < keys.length; i++)
    		w.write(prefix+keys[i]+'='+get(keys[i])+'\n');
    	
    	if(subsets != null) {
    		String[] orderedPrefixes = (String[]) subsets.keySet().toArray(new String[subsets.size()]);
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
    			w.write(endMarker+ '\n');
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

	public KeyIterator keyIterator(String prefix) {
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
    		while(true) {
    			if(valuesIterator.hasNext()) break;
    			if(!subsetIterator.hasNext()) break;
    			String name = (String) subsetIterator.next();
    			if(name == null) continue;
    			SimpleFieldSet fs = (SimpleFieldSet) subsets.get(name);
    			if(fs == null) continue;
    			String newPrefix = prefix + name + MULTI_LEVEL_CHAR;
    			subIterator = fs.keyIterator(newPrefix);
    			if(subIterator.hasNext()) break;
    			subIterator = null;
    		}
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
				if(valuesIterator != null && valuesIterator.hasNext()) {
					return prefix + valuesIterator.next();
				}
				while(true) {
					// Iterate subsets.
					if(subIterator != null && subIterator.hasNext()) {
						ret = (String) subIterator.next();
						if(subIterator.hasNext())
							if(ret != null) return ret;
					}
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
		if(!shortLived) key = key.intern();
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

	public static SimpleFieldSet readFrom(InputStream is, boolean allowMultiple, boolean shortLived) throws IOException {
		BufferedInputStream bis = null;
		InputStreamReader isr = null;
		BufferedReader br = null;
		
		try {
			bis = new BufferedInputStream(is);
			try {
				isr = new InputStreamReader(bis, "UTF-8");
			} catch (UnsupportedEncodingException e) {
				Logger.error(SimpleFieldSet.class, "Impossible: "+e, e);
				is.close();
				return null;
			}
			br = new BufferedReader(isr);
			SimpleFieldSet fs = new SimpleFieldSet(br, allowMultiple, shortLived);
			return fs;
		} finally {
			try {
				if(br != null) br.close();
				if(isr != null) isr.close();
				if(bis != null) bis.close();
			} catch (IOException e) {}			
		}
	}
	
	public static SimpleFieldSet readFrom(File f, boolean allowMultiple, boolean shortLived) throws IOException {
		return readFrom(new FileInputStream(f), allowMultiple, shortLived);
	}

	public int getInt(String key, int def) {
		String s = get(key);
		if(s == null) return def;
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return def;
		}
	}

	public int getInt(String key) throws FSParseException {
		String s = get(key);
		if(s == null) throw new FSParseException("No key "+key);
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			throw new FSParseException("Cannot parse "+s+" for integer "+key);
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

	public void put(String key, int[] value) {
		// FIXME this could be more efficient...
		removeValue(key);
		for(int i=0;i<value.length;i++)
			putAppend(key, Integer.toString(value[i]));
	}

	public int[] getIntArray(String key) {
		String[] strings = getAll(key);
		int[] ret = new int[strings.length];
		for(int i=0;i<strings.length;i++) {
			try {
				ret[i] = Integer.parseInt(strings[i]);
			} catch (NumberFormatException e) {
				Logger.error(this, "Cannot parse "+strings[i]+" : "+e, e);
				return null;
			}
		}
		return ret;
	}

}
