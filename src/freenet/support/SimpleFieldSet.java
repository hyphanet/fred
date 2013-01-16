package freenet.support;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import com.db4o.ObjectContainer;

import freenet.node.FSParseException;
import freenet.support.io.Closer;
import freenet.support.io.LineReader;
import freenet.support.io.Readers;

/**
 * @author amphibian
 *
 * Very very simple FieldSet type thing, which uses the standard
 * Java facilities.
 */
public class SimpleFieldSet {

    private final Map<String, String> values;
    private Map<String, SimpleFieldSet> subsets;
    private String endMarker;
    private final boolean shortLived;
    protected String[] header;

    public static final char MULTI_LEVEL_CHAR = '.';
    public static final char MULTI_VALUE_CHAR = ';';
    public static final char KEYVALUE_SEPARATOR_CHAR = '=';
    private static final String[] EMPTY_STRING_ARRAY = new String[0];

    /**
     * Create a SimpleFieldSet.
     * @param shortLived If false, strings will be interned to ensure that they use as
     * little memory as possible. Only set to true if the SFS will be short-lived or
     * small.
     */
    public SimpleFieldSet(boolean shortLived) {
        values = new HashMap<String, String>();
       	subsets = null;
       	this.shortLived = shortLived;
    }

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
        this(shortLived);
        read(Readers.fromBufferedReader(br), allowMultiple);
    }

    public SimpleFieldSet(SimpleFieldSet sfs){
    	values = new HashMap<String, String>(sfs.values);
    	if(sfs.subsets != null)
    		subsets = new HashMap<String, SimpleFieldSet>(sfs.subsets);
    	this.shortLived = false; // it's been copied!
    	this.header = sfs.header;
    	this.endMarker = sfs.endMarker;
    }

    public SimpleFieldSet(LineReader lis, int maxLineLength, int lineBufferSize, boolean utf8OrIso88591, boolean allowMultiple, boolean shortLived) throws IOException {
    	this(shortLived);
    	read(lis, maxLineLength, lineBufferSize, utf8OrIso88591, allowMultiple);
    }

    /**
     * Construct from a string.
     * String format:
     * blah=blah
     * blah=blah
     * End
     * @param shortLived If false, strings will be interned to ensure that they use as
     * little memory as possible. Only set to true if the SFS will be short-lived or
     * small.
     * @throws IOException if the string is too short or invalid.
     */
    public SimpleFieldSet(String content, boolean allowMultiple, boolean shortLived) throws IOException {
    	this(shortLived);
        StringReader sr = new StringReader(content);
        BufferedReader br = new BufferedReader(sr);
	    read(Readers.fromBufferedReader(br), allowMultiple);
    }
    
    /**
     * Construct from a {@link String} array.
     * <p>
     * Similar to {@link #SimpleFieldSet(String, boolean, boolean)},
     * but each item of array represents a single line
     * </p>
     * @param content to be parsed 
     * @param allowMultiple If {@code true}, multiple lines with the same field name will be
     * combined; if {@code false}, the constructor will throw.
     * @param shortLived If {@code false}, strings will be interned to ensure that they use as
     * little memory as possible. Only set to {@code true} if the SFS will be short-lived or
     * small.
     * @throws IOException
     */
    public SimpleFieldSet(String[] content, boolean allowMultiple, boolean shortLived) throws IOException {
    	this(shortLived);
    	read(Readers.fromStringArray(content), allowMultiple);
    }
    
    /**
     * @see #read(LineReader, int, int, boolean, boolean)
     */
	private void read(LineReader lr, boolean allowMultiple) throws IOException {
		read(lr, Integer.MAX_VALUE, 0x100, true, allowMultiple);
	}
	
	/**
	 * Read from stream. Format:
	 *
	 * # Header1
	 * # Header2
	 * key0=val0
	 * key1=val1
	 * # comment
	 * key2=val2
	 * End
	 *
	 * (headers and comments are optional)
	 *
	 * @param utfOrIso88591 If true, read as UTF-8, otherwise read as ISO-8859-1.
	 */
	private void read(LineReader br, int maxLength, int bufferSize, boolean utfOrIso88591, boolean allowMultiple) throws IOException {
		boolean firstLine = true;
		boolean headerSection = true;
		List<String> headers = new ArrayList<String>();

		while (true) {
			String line = br.readLine(maxLength, bufferSize, utfOrIso88591);
			if (line == null) {
				if (firstLine) throw new EOFException();
				Logger.error(this, "No end marker");
				break;
			}
			if ((line.length() == 0)) continue; // ignore
			firstLine = false;

			char first = line.charAt(0);
			if (first == '#') {
				if (headerSection) {
					headers.add(line.substring(1).trim());
				}

			} else {
				if (headerSection) {
					if (headers.size() > 0) { this.header = headers.toArray(new String[headers.size()]); }
					headerSection = false;
				}

				int index = line.indexOf(KEYVALUE_SEPARATOR_CHAR);
				if(index >= 0) {
					// Mapping
					String before = line.substring(0, index);
					String after = line.substring(index+1);
					if(!shortLived) after = after.intern();
					put(before, after, allowMultiple, false, true);
				} else {
					endMarker = line;
					break;
				}
			}
		}
	}

    public synchronized String get(String key) {
   		int idx = key.indexOf(MULTI_LEVEL_CHAR);
   		if(idx == -1)
   			return values.get(key);
   		else if(idx == 0)
			return (subset("") == null) ? null : subset("").get(key.substring(1));
		else {
   			if(subsets == null) return null;
   			String before = key.substring(0, idx);
   			String after = key.substring(idx+1);
   			SimpleFieldSet fs = subsets.get(before);
   			if(fs == null) return null;
   			return fs.get(after);
   		}
    }

    public String[] getAll(String key) {
    	String k = get(key);
    	if(k == null) return null;
    	return split(k);
    }

    private static String[] split(String string) {
    	if(string == null) return EMPTY_STRING_ARRAY;
    	return string.split(String.valueOf(MULTI_VALUE_CHAR)); // slower???
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

    private static String unsplit(String[] strings) {
		if (strings.length == 0) return "";
    	StringBuilder sb = new StringBuilder();
    	for(String s: strings) {
    		sb.append(s);
			sb.append(MULTI_VALUE_CHAR);
    	}
		// assert(sb.length() > 0) -- always true as strings.length != 0
		// remove last MULTI_VALUE_CHAR
		sb.deleteCharAt(sb.length()-1);
    	return sb.toString();
    }

    /**
     * Put contents of a fieldset, overwrite old values.
     */
    public void putAllOverwrite(SimpleFieldSet fs) {
    	for(Map.Entry<String, String> entry: fs.values.entrySet()) {
    		values.put(entry.getKey(), entry.getValue()); // overwrite old
    	}
    	if(fs.subsets == null) return;
	if(subsets == null) subsets = new HashMap<String, SimpleFieldSet>();
    	for(Map.Entry<String, SimpleFieldSet> entry: fs.subsets.entrySet()) {
    		String key = entry.getKey();
    		SimpleFieldSet hisFS = entry.getValue();
    		SimpleFieldSet myFS = subsets.get(key);
    		if(myFS != null) {
    			myFS.putAllOverwrite(hisFS);
    		} else {
    			subsets.put(key, hisFS);
    		}
    	}
    }

    /**
     * Set a key to a value. If the value already exists, throw IllegalStateException.
     * @param key The key.
     * @param value The value.
     */
    public void putSingle(String key, String value) {
    	if(value == null) return;
    	if(!shortLived) value = value.intern();
    	if(!put(key, value, false, false, false))
    		throw new IllegalStateException("Value already exists: "+value+" but want to set "+key+" to "+value);
    }

    /**
     * Aggregating put. Set a key to a value, if the value already exists, append to it.
     * If you do not need this functionality please use putOverwrite for a minimal
     * performance gain.
     *
     * @param key The key.
     * @param value The value.
     */
    public void putAppend(String key, String value) {
    	if(value == null) return;
    	if(!shortLived) value = value.intern();
    	put(key, value, true, false, false);
    }

    /**
     * Set a key to a value, overwriting any existing value if present.
     * This function is a little bit faster than putAppend() because it does not
     * check whether the key already exists.
     *
     * @param key The key.
     * @param value The value.
     */
    public void putOverwrite(String key, String value) {
    	if(value == null) return;
    	if(!shortLived) value = value.intern();
    	put(key, value, false, true, false);
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
	private synchronized boolean put(String key, String value, boolean allowMultiple, boolean overwrite, boolean fromRead) {
		int idx;
		if(value == null) return true; // valid no-op
		if(value.indexOf('\n') != -1) throw new IllegalArgumentException("A simplefieldSet can't accept newlines !");
		if(allowMultiple && (!fromRead) && value.indexOf(MULTI_VALUE_CHAR) != -1) {
			throw new IllegalArgumentException("Appending a string to a SimpleFieldSet value should not contain the multi-value char \""+String.valueOf(MULTI_VALUE_CHAR)+"\" but it does: \"" +value+"\" for \""+key+"\"", new Exception("error"));
		}
		if((idx = key.indexOf(MULTI_LEVEL_CHAR)) == -1) {
			if(!shortLived) key = key.intern();

			if(overwrite) {
				values.put(key, value);
			} else {
				if(values.get(key) == null) {
					values.put(key, value);
				} else {
					if(!allowMultiple) return false;
					values.put(key, (values.get(key))+ MULTI_VALUE_CHAR +value);
				}
			}
		} else {
			String before = key.substring(0, idx);
			String after = key.substring(idx+1);
			SimpleFieldSet fs = null;
			if(subsets == null)
				subsets = new HashMap<String, SimpleFieldSet>();
			fs = subsets.get(before);
			if(fs == null) {
				fs = new SimpleFieldSet(shortLived);
				if(!shortLived) before = before.intern();
				subsets.put(before, fs);
			}
			fs.put(after, value, allowMultiple, overwrite, fromRead);
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
		put(key, Boolean.toString(b), false, false, false);
	}

	public void put(String key, double windowSize) {
		putSingle(key, Double.toString(windowSize));
	}

    /**
     * Write the contents of the SimpleFieldSet to a Writer.
     * Note: The caller *must* buffer the writer to avoid lousy performance!
     * (StringWriter is by definition buffered, otherwise wrap it in a BufferedWriter)
     *
     * @warning keep in mind that a Writer is not necessarily UTF-8!!
     */
	public void writeTo(Writer w) throws IOException {
		writeTo(w, "", false);
	}

    /**
     * Write the contents of the SimpleFieldSet to a Writer.
     * Note: The caller *must* buffer the writer to avoid lousy performance!
     * (StringWriter is by definition buffered, otherwise wrap it in a BufferedWriter)
     *
     * @warning keep in mind that a Writer is not necessarily UTF-8!!
     */
    synchronized void writeTo(Writer w, String prefix, boolean noEndMarker) throws IOException {
		writeHeader(w);
    	for (Map.Entry<String, String> entry: values.entrySet()) {
			String key = entry.getKey();
			String value = entry.getValue();
            w.write(prefix);
            w.write(key);
            w.write(KEYVALUE_SEPARATOR_CHAR);
            w.write(value);
            w.write('\n');
    	}
    	if(subsets != null) {
    		for (Map.Entry<String, SimpleFieldSet> entry: subsets.entrySet()) {
				String key = entry.getKey();
				SimpleFieldSet subset = entry.getValue();
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
		writeHeader(w);
    	String[] keys = values.keySet().toArray(new String[values.size()]);
    	int i=0;

    	// Sort
    	Arrays.sort(keys);

    	// Output
    	for(i=0; i < keys.length; i++)
    		w.write(prefix+keys[i]+KEYVALUE_SEPARATOR_CHAR+get(keys[i])+'\n');

    	if(subsets != null) {
    		String[] orderedPrefixes = subsets.keySet().toArray(new String[subsets.size()]);
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

	private void writeHeader(Writer w) throws IOException {
		if (header != null) {
			for (String line: header) {
				w.write("# " + line + "\n");
			}
		}
	}

	@Override
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
			return subsets.get(key);
		String before = key.substring(0, idx);
		String after = key.substring(idx+1);
		SimpleFieldSet fs = subsets.get(before);
		if(fs == null) return null;
		return fs.subset(after);
	}

	/**
	 * Like subset(), only throws instead of returning null.
	 * @throws FSParseException
	 */
	public synchronized SimpleFieldSet getSubset(String key) throws FSParseException {
		SimpleFieldSet fs = subset(key);
		if(fs == null) throw new FSParseException("No such subset "+key);
		return fs;
	}

	public Iterator<String> keyIterator() {
		return new KeyIterator("");
	}

	public KeyIterator keyIterator(String prefix) {
		return new KeyIterator(prefix);
	}

        public Iterator<String> toplevelKeyIterator() {
            return values.keySet().iterator();
        }

    public class KeyIterator implements Iterator<String> {
    	final Iterator<String> valuesIterator;
    	final Iterator<String> subsetIterator;
    	KeyIterator subIterator;
    	String prefix;

    	/**
    	 * It provides an iterator for the SimpleSetField
    	 * which passes through every key.
    	 * (e.g. for key1=value1 key2.sub2=value2 key1.sub=value3
    	 * it will provide key1,key2.sub2,key1.sub)
    	 * @param a prefix to put BEFORE every key
    	 * (e.g. for key1=value, if the iterator is created with prefix "aPrefix",
    	 * it will provide aPrefixkey1
    	 */
    	public KeyIterator(String prefix) {
    		synchronized(SimpleFieldSet.this) {
    			valuesIterator = values.keySet().iterator();
    			if(subsets != null)
    				subsetIterator = subsets.keySet().iterator();
    			else
    				subsetIterator = null;
    			while(true) {
    				if(valuesIterator != null && valuesIterator.hasNext()) break;
    				if(subsetIterator == null || !subsetIterator.hasNext()) break;
    				String name = subsetIterator.next();
    				if(name == null) continue;
    				SimpleFieldSet fs = subsets.get(name);
    				if(fs == null) continue;
    				String newPrefix = prefix + name + MULTI_LEVEL_CHAR;
    				subIterator = fs.keyIterator(newPrefix);
    				if(subIterator.hasNext()) break;
    				subIterator = null;
    			}
    			this.prefix = prefix;
    		}
    	}

		@Override
		public boolean hasNext() {
			synchronized(SimpleFieldSet.this) {
				while(true) {
					if(valuesIterator.hasNext()) return true;
					if((subIterator != null) && subIterator.hasNext()) return true;
					if(subIterator != null) subIterator = null;
					if(subsetIterator != null && subsetIterator.hasNext()) {
						String key = subsetIterator.next();
						SimpleFieldSet fs = subsets.get(key);
						String newPrefix = prefix + key + MULTI_LEVEL_CHAR;
						subIterator = fs.keyIterator(newPrefix);
					} else
						return false;
				}
			}
		}

		@Override
		public final String next() {
			return nextKey();
		}

		public String nextKey() {
			synchronized(SimpleFieldSet.this) {
				String ret = null;
				if(valuesIterator != null && valuesIterator.hasNext()) {
					return prefix + valuesIterator.next();
				}
				// Iterate subsets.
				while(true) {
					if(subIterator != null && subIterator.hasNext()) {
						// If we have a retval, and we have a next value, return
						if(ret != null) return ret;
						ret = subIterator.next();
						if(subIterator.hasNext())
							// If we have a retval, and we have a next value, return
							if(ret != null) return ret;
					}
					// Otherwise, we need to get a new subIterator (or hasNext() will return false)
					subIterator = null;
					if(subsetIterator != null && subsetIterator.hasNext()) {
						String key = subsetIterator.next();
						SimpleFieldSet fs = subsets.get(key);
						String newPrefix = prefix + key + MULTI_LEVEL_CHAR;
						subIterator = fs.keyIterator(newPrefix);
					} else {
						// No more subIterator's
						if(ret == null) {
							//There is nothing to return and no more iterators, so we must be out
							//of elements
							throw new NoSuchElementException();
						}
						return ret;
					}
				}
			}
		}

		@Override
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
			subsets = new HashMap<String, SimpleFieldSet>();
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
			SimpleFieldSet fs = subsets.get(before);
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

	/**
	 * It removes the specified subset.
	 * For example, in a SimpleFieldSet like this:
	 * foo=bar
	 * foo.bar=foobar
	 * foo.bar.boo=foobarboo
	 * calling it with the parameter "foo"
	 * means to drop the second and the third line.
	 * @param is the subset to remove
	 */
	public synchronized void removeSubset(String key) {
		if(subsets == null) return;
		int idx;
		if((idx = key.indexOf(MULTI_LEVEL_CHAR)) == -1) {
			subsets.remove(key);
		} else {
			String before = key.substring(0, idx);
			String after = key.substring(idx+1);
			SimpleFieldSet fs = subsets.get(before);
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
	public synchronized boolean isEmpty() {
		return values.isEmpty() && (subsets == null || subsets.isEmpty());
	}

	public Iterator<String> directSubsetNameIterator() {
		return (subsets == null) ? null : subsets.keySet().iterator();
	}

	public String[] namesOfDirectSubsets() {
		return (subsets == null) ? EMPTY_STRING_ARRAY : subsets.keySet().toArray(new String[subsets.size()]);
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
				throw new Error("Impossible: JVM doesn't support UTF-8: " + e, e);
			}
			br = new BufferedReader(isr);
			SimpleFieldSet fs = new SimpleFieldSet(br, allowMultiple, shortLived);
			br.close();

			return fs;
		} finally {
                        Closer.close(br);
                        Closer.close(isr);
                        Closer.close(bis);
                }
	}

	public static SimpleFieldSet readFrom(File f, boolean allowMultiple, boolean shortLived) throws IOException {
		return readFrom(new FileInputStream(f), allowMultiple, shortLived);
	}

	/** Write to the given OutputStream and flush it. */
        public void writeTo(OutputStream os) throws IOException {
            BufferedOutputStream bos = null;
            OutputStreamWriter osw = null;
            BufferedWriter bw = null;

            bos = new BufferedOutputStream(os);
            try {
            	osw = new OutputStreamWriter(bos, "UTF-8");
            } catch (UnsupportedEncodingException e) {
            	Logger.error(SimpleFieldSet.class, "Impossible: " + e, e);
            	throw e;
            }
            bw = new BufferedWriter(osw);
            writeTo(bw);
            bw.flush();
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

	public double getDouble(String key) throws FSParseException {
		String s = get(key);
		if(s == null) throw new FSParseException("No key "+key);
		try {
			return Double.parseDouble(s);
		} catch (NumberFormatException e) {
			throw new FSParseException("Cannot parse "+s+" for integer "+key);
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

	public long getLong(String key) throws FSParseException {
		String s = get(key);
		if(s == null) throw new FSParseException("No key "+key);
		try {
			return Long.parseLong(s);
		} catch (NumberFormatException e) {
			throw new FSParseException("Cannot parse "+s+" for long "+key);
		}
	}

	public short getShort(String key) throws FSParseException {
		String s = get(key);
		if(s == null) throw new FSParseException("No key "+key);
		try {
			return Short.parseShort(s);
		} catch (NumberFormatException e) {
			throw new FSParseException("Cannot parse "+s+" for short "+key);
		}
	}

	public short getShort(String key, short def) {
		String s = get(key);
		if(s == null) return def;
		try {
			return Short.parseShort(s);
		} catch (NumberFormatException e) {
			return def;
		}
	}

	public byte getByte(String key) throws FSParseException {
		String s = get(key);
		if(s == null) throw new FSParseException("No key " + key);
		try {
			return Byte.parseByte(s);
		} catch (NumberFormatException e) {
			throw new FSParseException("Cannot parse \"" + s + "\" as a byte.");
		}
	}

	public byte getByte(String key, byte def) {
		try {
			return getByte(key);
		} catch (FSParseException e) {
			return def;
		}
	}

	public char getChar(String key) throws FSParseException {
		String s = get(key);
		if(s == null) throw new FSParseException("No key "+key);
			if (s.length() == 1)
				return s.charAt(0);
			else
				throw new FSParseException("Cannot parse "+s+" for char "+key);
	}

	public char getChar(String key, char def) {
		String s = get(key);
		if(s == null) return def;
			if (s.length() == 1)
				return s.charAt(0);
			else
				return def;
	}

	public boolean getBoolean(String key, boolean def) {
		return Fields.stringToBool(get(key), def);
	}

	public boolean getBoolean(String key) throws FSParseException {
		try {
		    return Fields.stringToBool(get(key));
		} catch(NumberFormatException e) {
		    throw new FSParseException(e);
		}
	}

	public void put(String key, int[] value) {
		removeValue(key);
		for(int v : value)
			putAppend(key, String.valueOf(v));
	}

	public void put(String key, double[] value) {
		removeValue(key);
		for(double v : value)
			putAppend(key, String.valueOf(v));
	}

	public void put(String key, float[] value) {
		removeValue(key);
		for (float v : value) putAppend(key, String.valueOf(v));
	}

	public int[] getIntArray(String key) {
		String[] strings = getAll(key);
		if(strings == null) return null;
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

	public double[] getDoubleArray(String key) {
		String[] strings = getAll(key);
		if(strings == null) return null;
		double[] ret = new double[strings.length];
		for(int i=0;i<strings.length;i++) {
			try {
				ret[i] = Double.valueOf(strings[i]);
			} catch(NumberFormatException e) {
				Logger.error(this, "Cannot parse "+strings[i]+" : "+e,e);
				return null;
			}
		}

		return ret;
	}

	public float[] getFloatArray(String key) {
		String[] strings = getAll(key);
		if(strings == null) return null;
		float[] ret = new float[strings.length];
		for(int i=0;i<strings.length;i++) {
			try {
				ret[i] = Float.valueOf(strings[i]);
			} catch(NumberFormatException e) {
				Logger.error(this, "Cannot parse "+strings[i]+" : "+e,e);
				return null;
			}
		}

		return ret;
	}

	public void putOverwrite(String key, String[] strings) {
		putOverwrite(key, unsplit(strings));
	}

	public String getString(String key) throws FSParseException {
		String s = get(key);
		if(s == null) throw new FSParseException("No such element "+key);
		return s;
	}

	public void removeFrom(ObjectContainer container) {
		container.delete(values);
		for(SimpleFieldSet fs : subsets.values())
			fs.removeFrom(container);
		container.delete(subsets);
		container.delete(this);
	}

	public void setHeader(String... headers) {
		// FIXME LOW should really check that each line doesn't have a "\n" in it
		this.header = headers;
	}

	public String[] getHeader() {
		return this.header;
	}

}
