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
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import freenet.node.FSParseException;
import freenet.support.io.Closer;
import freenet.support.io.LineReader;
import freenet.support.io.Readers;

/**
 * @author amphibian
 *
 * Simple FieldSet type thing, which uses the standard Java facilities. Should always be written as 
 * UTF-8. Simpler encoding than Properties.
 * 
 * All of the methods treat the data as a tree, where levels are indicated by ".". Hence e.g.:
 * DirectKey=Value
 * Subset.Key=Value
 * Subset.Subset.Key=Value
 * 
 * DETAILS:
 * <key>=<value> 
 * Key is split into a tree via "."'s.
 * Value is a string.
 * Conversion methods are provided for most key types, one notable issue is arrays of string, 
 * which are separated by ";".
 * 
 * ALTERNATE FORMAT:
 * <key>==<standard base64 encoded value>
 * The value is encoded. We will use this later on to prevent problems when transferring noderefs 
 * (line breaks, whitespace get changes when people paste stuff etc), and to allow e.g. newlines 
 * in strings. For now we only *read* such formats.
 */
public class SimpleFieldSet {

    private final Map<String, String> values;
    private Map<String, SimpleFieldSet> subsets;
    private String endMarker;
    private final boolean shortLived;
    private final boolean alwaysUseBase64;
    protected String[] header;

    public static final char MULTI_LEVEL_CHAR = '.';
    public static final char MULTI_VALUE_CHAR = ';';
    public static final char KEYVALUE_SEPARATOR_CHAR = '=';
    private static final String[] EMPTY_STRING_ARRAY = new String[0];

    public SimpleFieldSet(boolean shortLived) {
        this(shortLived, false);
    }
    
    /**
     * Create a SimpleFieldSet.
     * @param shortLived If false, strings will be interned to ensure that they use as
     * little memory as possible. Only set to true if the SFS will be short-lived or
     * small.
     * @param alwaysUseBase64 If true, the SFS can contain newlines etc in values, and we will
     * always use base64 for the values if they contain such invalid characters.
     */
    public SimpleFieldSet(boolean shortLived, boolean alwaysUseBase64) {
        values = new HashMap<String, String>();
       	subsets = null;
       	this.shortLived = shortLived;
       	this.alwaysUseBase64 = alwaysUseBase64;
    }

    public SimpleFieldSet(BufferedReader br, boolean allowMultiple, boolean shortLived) throws IOException {
        this(br, allowMultiple, shortLived, false, false);
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
    public SimpleFieldSet(BufferedReader br, boolean allowMultiple, boolean shortLived, boolean allowBase64, boolean alwaysBase64) throws IOException {
        this(shortLived, alwaysBase64);
        read(Readers.fromBufferedReader(br), allowMultiple, allowBase64);
    }

    /** Copy constructor */
    public SimpleFieldSet(SimpleFieldSet sfs){
    	values = new HashMap<String, String>(sfs.values);
    	if(sfs.subsets != null)
    		subsets = new HashMap<String, SimpleFieldSet>(sfs.subsets);
    	this.shortLived = false; // it's been copied!
    	this.header = sfs.header;
    	this.endMarker = sfs.endMarker;
    	this.alwaysUseBase64 = sfs.alwaysUseBase64;
    }

    public SimpleFieldSet(LineReader lis, int maxLineLength, int lineBufferSize, boolean utf8OrIso88591, boolean allowMultiple, boolean shortLived) throws IOException {
    	this(lis, maxLineLength, lineBufferSize, utf8OrIso88591, allowMultiple, shortLived, false);
    }

    public SimpleFieldSet(LineReader lis, int maxLineLength, int lineBufferSize, boolean utf8OrIso88591, boolean allowMultiple, boolean shortLived, boolean allowBase64) throws IOException {
    	this(shortLived);
    	read(lis, maxLineLength, lineBufferSize, utf8OrIso88591, allowMultiple, allowBase64);
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
    public SimpleFieldSet(String content, boolean allowMultiple, boolean shortLived, boolean allowBase64) throws IOException {
    	this(shortLived);
        StringReader sr = new StringReader(content);
        BufferedReader br = new BufferedReader(sr);
	    read(Readers.fromBufferedReader(br), allowMultiple, allowBase64);
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
    public SimpleFieldSet(String[] content, boolean allowMultiple, boolean shortLived, boolean allowBase64) throws IOException {
    	this(shortLived);
    	read(Readers.fromStringArray(content), allowMultiple, allowBase64);
    }
    
    /**
     * @see #read(LineReader, int, int, boolean, boolean)
     */
	private void read(LineReader lr, boolean allowMultiple, boolean allowBase64) throws IOException {
		read(lr, Integer.MAX_VALUE, 0x100, true, allowMultiple, allowBase64);
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
	private void read(LineReader br, int maxLength, int bufferSize, boolean utfOrIso88591, boolean allowMultiple, boolean allowBase64) throws IOException {
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
					String before = line.substring(0, index).trim();
					String after = line.substring(index+1);
					if((!after.isEmpty()) && after.charAt(0) == '=' && allowBase64) {
						try {
							after = after.substring(1);
							after = after.replaceAll("\\s", "");
							after = Base64.decodeUTF8(after);
						} catch (IllegalBase64Exception e) {
							throw new IOException("Unable to decode UTF8, = should not be allowed as first character of a value");
						}
					}
					if(!shortLived) after = after.intern();
					put(before, after, allowMultiple, false, true);
				} else {
					endMarker = line;
					break;
				}
			}
		}
	}

	/** Get a value for a key as a String. This may be a top level value, or we will traverse the 
	 * tree, so can be used for any key=value or subset.subset.key=value etc.
	 * @param key The key to look up.
	 * @return The String value corresponding to the given key, or null if there is no such 
	 * key=value pair.
	 */
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

    /** Get a list of String's from a single value, encoded in Base64. This is useful for storing
     * arbitrary String's that may contain illegal characters - the MULTI_VALUE_CHAR, newlines, etc.
     */
    public String[] getAllEncoded(String key) throws IllegalBase64Exception {
        String k = get(key);
        if(k == null) return null;
        String[] ret = split(k);
        for(int i=0;i<ret.length;i++) {
            ret[i] = Base64.decodeUTF8(ret[i]);
        }
        return ret;
    }

    /** Split a set of String's delimeted by MULTI_VALUE_CHAR, accepting empty strings at both ends.
     * E.g. ";blah;blah;blah;;" will give ["", "blah", "blah", "blah", "", ""].
     * Java 7 split() would give ["blah","blah","blah"].
     */
    public static String[] split(String string) {
    	if(string == null) return EMPTY_STRING_ARRAY;
    	// Java 7 version of String.split() trims the extra delimeters at each end.
    	int emptyAtStart = 0;
    	for(;emptyAtStart<string.length() && string.charAt(emptyAtStart) == MULTI_VALUE_CHAR;emptyAtStart++);
    	if(emptyAtStart == string.length()) {
    	    String[] ret = new String[string.length()];
    	    for(int i=0;i<ret.length;i++) ret[i] = "";
    	    return ret;
    	}
    	int emptyAtEnd = 0;
    	for(int i=string.length()-1; i>=0 && string.charAt(i) == MULTI_VALUE_CHAR;i--) emptyAtEnd++;
    	string = string.substring(emptyAtStart, string.length() - emptyAtEnd);
    	String[] split = string.split(String.valueOf(MULTI_VALUE_CHAR)); // slower???
    	if(emptyAtStart != 0 || emptyAtEnd != 0) {
    	    String[] ret = new String[emptyAtStart+split.length+emptyAtEnd];
    	    System.arraycopy(split, 0, ret, emptyAtStart, split.length);
    	    split = ret;
    	    for(int i=0;i<split.length;i++)
    	        if(split[i] == null) split[i] = "";
    	}
    	return split;
	}

    /** Combine a list of String's into a single String, separating them by the MULTI_VALUE_CHAR. */
    private static String unsplit(String[] strings) {
		if (strings.length == 0) return "";
    	StringBuilder sb = new StringBuilder();
    	for(String s: strings) {
    		sb.append(s);
    		assert(s.indexOf(MULTI_VALUE_CHAR) == -1);
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
		if((!alwaysUseBase64) && value.indexOf('\n') != -1) throw new IllegalArgumentException("A simplefieldSet can't accept newlines !");
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
				fs = new SimpleFieldSet(shortLived, alwaysUseBase64);
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
	
	public void put(String key, byte[] bytes) {
	    putSingle(key, Base64.encode(bytes));
	}

    /**
     * Write the contents of the SimpleFieldSet to a Writer.
     * Note: The caller *must* buffer the writer to avoid lousy performance!
     * (StringWriter is by definition buffered, otherwise wrap it in a BufferedWriter)
     *
     * @warning keep in mind that a Writer is not necessarily UTF-8!!
     */
	public void writeTo(Writer w) throws IOException {
		writeTo(w, "", false, false);
	}

    /**
     * Write the contents of the SimpleFieldSet to a Writer.
     * Note: The caller *must* buffer the writer to avoid lousy performance!
     * (StringWriter is by definition buffered, otherwise wrap it in a BufferedWriter)
     * 
     * @param w The Writer to write to. @warning keep in mind that a Writer is not necessarily UTF-8!!
     * @param prefix String to prefix the keys with. (E.g. when writing a tree of SFS's).
     * @param noEndMarker If true, don't write the end marker (the last line, the only one with no
     * "=" in it).
     * @param useBase64 If true, use Base64 for any value that has control characters, whitespace, 
     * or characters used by SimpleFieldSet in it. In this case the separator will be "==" not "=".
     * This is mainly useful for node references, which tend to lose whitespace, gain newlines etc
     * in transit. Can be overridden (to true) by alwaysUseBase64 setting.
     */
    synchronized void writeTo(Writer w, String prefix, boolean noEndMarker, boolean useBase64) throws IOException {
		writeHeader(w);
    	for (Map.Entry<String, String> entry: values.entrySet()) {
			String key = entry.getKey();
			String value = entry.getValue();
			writeValue(w, key, value, prefix, useBase64);
    	}
    	if(subsets != null) {
    		for (Map.Entry<String, SimpleFieldSet> entry: subsets.entrySet()) {
				String key = entry.getKey();
				SimpleFieldSet subset = entry.getValue();
    			if(subset == null) throw new NullPointerException();
    			subset.writeTo(w, prefix+key+MULTI_LEVEL_CHAR, true, useBase64);
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

    private void writeValue(Writer w, String key, String value, String prefix, boolean useBase64) throws IOException {
        w.write(prefix);
        w.write(key);
        w.write(KEYVALUE_SEPARATOR_CHAR);
        if((useBase64 || alwaysUseBase64) && shouldBase64(value)) {
        	w.write(KEYVALUE_SEPARATOR_CHAR);
        	w.write(Base64.encodeUTF8(value));
        } else {
        	w.write(value);
        }
        w.write('\n');
	}

	private boolean shouldBase64(String value) {
    	for(int i=0;i<value.length();i++) {
    		char c = value.charAt(i);
    		if(c == SimpleFieldSet.KEYVALUE_SEPARATOR_CHAR) return true;
    		if(c == SimpleFieldSet.MULTI_LEVEL_CHAR) return true;
    		if(c == SimpleFieldSet.MULTI_VALUE_CHAR) return true;
    		if(Character.isISOControl(c)) return true;
    		if(Character.isWhitespace(c)) return true;
    	}
    	return false;
	}

	public void writeToOrdered(Writer w) throws IOException {
		writeToOrdered(w, "", false, false);
	}

	/**
	 * Write the SimpleFieldSet to a Writer, in order.
	 * @param w The Writer to write the SFS to.
	 * @param prefix The prefix ("" at the top level, e.g. "something." if we are inside a sub-SFS 
	 * called "something").
	 * @param noEndMarker If true, don't write the end marker (usually End). Again this is normally
	 * set only in sub-SFS's.
	 * @param allowOptionalBase64 If true, write fields as Base64 if they contain spaces etc. This
	 * improves the robustness of e.g. node references, where the SFS can be written with or 
	 * without Base64. However, for SFS's where the values can contain <b>anything</b>, the member
	 * flag alwaysUseBase64 will be set and we will write lines that need to be Base64 as such 
	 * regardless of this allowOptionalBase64.
	 * @throws IOException If an error occurs writing to the Writer.
	 */
    private synchronized void writeToOrdered(Writer w, String prefix, boolean noEndMarker, boolean allowOptionalBase64) throws IOException {
		writeHeader(w);
    	String[] keys = values.keySet().toArray(new String[values.size()]);
    	int i=0;

    	// Sort
    	Arrays.sort(keys);

    	// Output
    	for(i=0; i < keys.length; i++) {
    		writeValue(w, keys[i], get(keys[i]), prefix, allowOptionalBase64);
    	}

    	if(subsets != null) {
    		String[] orderedPrefixes = subsets.keySet().toArray(new String[subsets.size()]);
    		// Sort
    		Arrays.sort(orderedPrefixes);

        	for(i=0; i < orderedPrefixes.length; i++) {
    			SimpleFieldSet subset = subset(orderedPrefixes[i]);
    			if(subset == null) throw new NullPointerException();
    			subset.writeToOrdered(w, prefix+orderedPrefixes[i]+MULTI_LEVEL_CHAR, true, allowOptionalBase64);
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

    public String toOrderedStringWithBase64() {
        StringWriter sw = new StringWriter();
        try {
            writeToOrdered(sw, "", false, true);
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

	/** Iterate over all keys in the SimpleFieldSet, even if they are at lower levels. */
	public Iterator<String> keyIterator() {
		return new KeyIterator("");
	}

	/** Iterate over all keys in the SimpleFieldSet, even if they are at lower levels. 
	 * @param prefix Add the given prefix to lower levels. This is used recursively by KeyIterator.
	 */
	public KeyIterator keyIterator(String prefix) {
		return new KeyIterator(prefix);
	}

	/** Iterate over keys that are in the top level of the tree, i.e. that do not contain a ".". 
	 * E.g. "Name=Value" is a top level key. "Subset.Name=Value" is NOT a top level key. */
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
    
    /** Get a read-only map of direct key name:value pairs. Direct key values are things like 
     * "Name=Value" (which would return a map containing "Name" -> "Value", NOT 
     * "Subset.Name=Value" (which would not be returned). */
    public Map<String, String> directKeyValues() {
        return Collections.unmodifiableMap(values);
    }

    /** Get a read-only set of direct key names. So:
     * Name=Value
     * Subset.OtherName=Value
     * End
     * Would give "Name".
     * @return
     */
    public Set<String> directKeys() {
        return Collections.unmodifiableSet(values.keySet());
    }

    /** Get a read-only set of direct subsets. So:
     * Name=Value
     * Subset.OtherName=Value
     * End
     * Would give "OtherName" -> SFS containing OtherName=Value.
     * @return
     */
    public Map<String, SimpleFieldSet> directSubsets() {
        return Collections.unmodifiableMap(subsets);
    }

    /** Tolerant put(); does nothing if fs is empty */
    public void tput(String key, SimpleFieldSet fs) {
    	if(fs == null || fs.isEmpty()) return;
    	put(key, fs);
    }

    /** Add a name:value pair, traversing the tree and creating sub-SFS's if necessary. So we can
     * add("a.b.c.d", "value) even if there is no subset "a"; it will create it automatically.
     * @param key Name of the key to add.
     * @param fs Subset under the key.
     */
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

	/** Remove a name:value pair at any point in the tree. Will automatically traverse the tree and 
	 * remove empty subsets (which are not written anyway). */
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

	/** Iterator over the names of direct subsets, i.e. the tree nodes just below this one, not the
	 * values. E.g.:
	 * Foo.Bar.Bat=1
	 * Baz.Boo=hello
	 * Grrr=goodbye
	 * End
	 * Returns "Foo", "Baz".
	 */
	public Iterator<String> directSubsetNameIterator() {
		return (subsets == null) ? null : subsets.keySet().iterator();
	}

	/** Get the names of direct subsets, i.e. the tree nodes just below this one, not the
     * values. E.g.:
     * Foo.Bar.Bat=1
     * Baz.Boo=hello
     * Grrr=goodbye
     * End
     * Returns [ "Foo", "Baz" ].
     */
	public String[] namesOfDirectSubsets() {
		return (subsets == null) ? EMPTY_STRING_ARRAY : subsets.keySet().toArray(new String[subsets.size()]);
	}

	/** Read a SimpleFieldSet from an InputStream in the standard format, using UTF-8, and not 
	 * allowing the Base64 encoding.
	 * @param is The InputStream to read from. We will use the UTF-8 charset.
     * @param allowMultiple Whether to allow multiple entries for each key (and automatically 
     * combine them). Not usually useful except maybe in FCP.
     * @param shortLived If true, don't intern the strings.
     * @return A new SimpleFieldSet.
     * @throws IOException If a read error occurs, including a formatting error, illegal 
     * characters etc.
	 */
	public static SimpleFieldSet readFrom(InputStream is, boolean allowMultiple, boolean shortLived) throws IOException {
	    return readFrom(is, allowMultiple, shortLived, false, false);
	}

	/**
	 * Read a SimpleFieldSet from an InputStream.
	 * @param is The InputStream to read from. We will use the UTF-8 charset.
	 * @param allowMultiple Whether to allow multiple entries for each key (and automatically 
	 * combine them). Not usually useful except maybe in FCP.
	 * @param shortLived If true, don't intern the strings.
	 * @param allowBase64 If true, allow reading Base64 encoded lines (key==base64(value)).
	 * @param alwaysBase64 If true, the resulting SFS should have the alwaysUseBase64 flag enabled, 
	 * i.e it can store anything in key values including newlines, special chars such as = etc. 
	 * Otherwise, even if allowBase64 is enabled, invalid chars will not be.
	 * @return A new SimpleFieldSet.
	 * @throws IOException If a read error occurs, including a formatting error, illegal 
	 * characters etc.
	 */
	public static SimpleFieldSet readFrom(InputStream is, boolean allowMultiple, boolean shortLived, boolean allowBase64, boolean alwaysBase64) throws IOException {
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
			SimpleFieldSet fs = new SimpleFieldSet(br, allowMultiple, shortLived, allowBase64, alwaysBase64);
			br.close();

			return fs;
		} finally {
                        Closer.close(br);
                        Closer.close(isr);
                        Closer.close(bis);
                }
	}

	/** Read a SimpleFieldSet from a File. */
	public static SimpleFieldSet readFrom(File f, boolean allowMultiple, boolean shortLived) throws IOException {
	    FileInputStream fis = new FileInputStream(f);
	    try {
	        return readFrom(fis, allowMultiple, shortLived);
	    } finally {
	        fis.close();
	    }
	}

    /** Write to the given OutputStream (as UTF-8) and flush it. */
    public void writeTo(OutputStream os) throws IOException {
        writeTo(os, 4096);
    }   
    
	/** Write to the given OutputStream and flush it. Use a big buffer, for jobs that aren't called
	 * too often e.g. persisting a file every 10 minutes. */
    public void writeToBigBuffer(OutputStream os) throws IOException {
    	writeTo(os, 65536);
    }	
	
	/** Write to the given OutputStream and flush it. */
    public void writeTo(OutputStream os, int bufferSize) throws IOException {
        BufferedOutputStream bos = null;
        OutputStreamWriter osw = null;
        BufferedWriter bw = null;
        
        bos = new BufferedOutputStream(os, bufferSize);
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

    /** Get an integer value for the given key. This may be at the top level or lower in the tree,
     * it's just key=value. (Value in decimal)
     * @param key The key to fetch.
     * @param def The default value to return if the key does not exist or can't be parsed.
     * @return The integer value of the key, or the default value.
     */
	public int getInt(String key, int def) {
		String s = get(key);
		if(s == null) return def;
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return def;
		}
	}

    /** Get an integer value for the given key. This may be at the top level or lower in the tree,
     * it's just key=value. (Value in decimal)
     * @param key The key to fetch.
     * @return The integer value of the key, if it exists and is valid.
     * @throws FSParseException If the key=value pair does not exist or if the value cannot be 
     * parsed as an integer.
     */
	public int getInt(String key) throws FSParseException {
		String s = get(key);
		if(s == null) throw new FSParseException("No key "+key);
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			throw new FSParseException("Cannot parse "+s+" for integer "+key);
		}
	}

    /** Get a double precision value for the given key. This may be at the top level or lower in 
     * the tree, it's just key=value. (Value in decimal)
     * @param key The key to fetch.
     * @param def The default value to return if the key does not exist or can't be parsed.
     * @return The integer value of the key, or the default value.
     */
	public double getDouble(String key, double def) {
		String s = get(key);
		if(s == null) return def;
		try {
			return Double.parseDouble(s);
		} catch (NumberFormatException e) {
			return def;
		}
	}

    /** Get a double precision value for the given key. This may be at the top level or lower in 
     * the tree, it's just key=value. (Value in decimal)
     * @param key The key to fetch.
     * @return The value of the key as a double, if it exists and is valid.
     * @throws FSParseException If the key=value pair does not exist or if the value cannot be 
     * parsed as a double.
     */
	public double getDouble(String key) throws FSParseException {
		String s = get(key);
		if(s == null) throw new FSParseException("No key "+key);
		try {
			return Double.parseDouble(s);
		} catch (NumberFormatException e) {
			throw new FSParseException("Cannot parse "+s+" for integer "+key);
		}
	}

    /** Get a long value for the given key. This may be at the top level or lower in the tree, 
     * it's just key=value. (Value in decimal)
     * @param key The key to fetch.
     * @param def The default value to return if the key does not exist or can't be parsed.
     * @return The long value of the key, or the default value.
     */
	public long getLong(String key, long def) {
		String s = get(key);
		if(s == null) return def;
		try {
			return Long.parseLong(s);
		} catch (NumberFormatException e) {
			return def;
		}
	}

    /** Get a long value for the given key. This may be at the top level or lower in the tree, 
     * it's just key=value. (Value in decimal)
     * @param key The key to fetch.
     * @return The value of the key as a long, if it exists and is valid.
     * @throws FSParseException If the key=value pair does not exist or if the value cannot be 
     * parsed as a long.
     */
	public long getLong(String key) throws FSParseException {
		String s = get(key);
		if(s == null) throw new FSParseException("No key "+key);
		try {
			return Long.parseLong(s);
		} catch (NumberFormatException e) {
			throw new FSParseException("Cannot parse "+s+" for long "+key);
		}
	}

    /** Get a short value for the given key. This may be at the top level or lower in the tree, 
     * it's just key=value. (Value in decimal)
     * @param key The key to fetch.
     * @return The value of the key as a short, if it exists and is valid.
     * @throws FSParseException If the key=value pair does not exist or if the value cannot be 
     * parsed as a short.
     */
	public short getShort(String key) throws FSParseException {
		String s = get(key);
		if(s == null) throw new FSParseException("No key "+key);
		try {
			return Short.parseShort(s);
		} catch (NumberFormatException e) {
			throw new FSParseException("Cannot parse "+s+" for short "+key);
		}
	}

    /** Get a short value for the given key. This may be at the top level or lower in the tree, 
     * it's just key=value. (Value in decimal)
     * @param key The key to fetch.
     * @return The value of the key as a short, if it exists and is valid.
     */
	public short getShort(String key, short def) {
		String s = get(key);
		if(s == null) return def;
		try {
			return Short.parseShort(s);
		} catch (NumberFormatException e) {
			return def;
		}
	}

	/** Get a byte value for the given key (represented as a number in decimal). This may be at 
	 * the top level or lower in the tree, it's just key=value. (Value in decimal)
     * @param key The key to fetch.
     * @return The value of the key as a byte, if it exists and is valid.
     * @throws FSParseException If the key=value pair does not exist or if the value cannot be 
     * parsed as a byte.
     */
	public byte getByte(String key) throws FSParseException {
		String s = get(key);
		if(s == null) throw new FSParseException("No key " + key);
		try {
			return Byte.parseByte(s);
		} catch (NumberFormatException e) {
			throw new FSParseException("Cannot parse \"" + s + "\" as a byte.");
		}
	}

    /** Get a byte value for the given key (represented as a number in decimal). This may be at 
     * the top level or lower in the tree, it's just key=value. (Value in decimal)
     * @param key The key to fetch.
     * @return The value of the key as a byte, if it exists and is valid, otherwise the default
     * value.
     */
	public byte getByte(String key, byte def) {
		try {
			return getByte(key);
		} catch (FSParseException e) {
			return def;
		}
	}

	/** Get a byte array for the given key (represented in Base64). The key may be at the top level
	 * or further down the tree, so this is key=[base64 of value].
	 * @param key The key to fetch.
	 * @return The byte array to fetch.
	 * @throws FSParseException If the key does not exist or cannot be parsed as a byte array.
	 */
	public byte[] getByteArray(String key) throws FSParseException {
        String s = get(key);
        if(s == null) throw new FSParseException("No key " + key);
        try {
            return Base64.decode(s);
        } catch (IllegalBase64Exception e) {
            throw new FSParseException("Cannot parse value \""+s+"\" as a byte[]");
        }
	}

	/** Get a char for the given key (represented as a single character). The key may be at the 
	 * top level or further down the tree, so this is key=[character].
     * @param key The key to fetch.
	 * @return The character to fetch.
	 * @throws FSParseException If the key does not exist or there is more than one character.
	 */
	public char getChar(String key) throws FSParseException {
		String s = get(key);
		if(s == null) throw new FSParseException("No key "+key);
			if (s.length() == 1)
				return s.charAt(0);
			else
				throw new FSParseException("Cannot parse "+s+" for char "+key);
	}

    /** Get a char for the given key (represented as a single character). The key may be at the 
     * top level or further down the tree, so this is key=[character].
     * @param key The key to fetch.
     * @param def The default value to return if the key does not exist or can't be parsed.
     * @return The character to fetch.
     * @throws FSParseException If the key does not exist or there is more than one character.
     */
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
	
    public void put(String key, short[] value) {
        removeValue(key);
        for (short v : value) putAppend(key, String.valueOf(v));
    }
    
    public void put(String key, long[] value) {
        removeValue(key);
        for (long v : value) putAppend(key, String.valueOf(v));
    }
    
    public void put(String key, boolean[] value) {
        removeValue(key);
        for (boolean v : value) putAppend(key, String.valueOf(v));
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

    public short[] getShortArray(String key) {
        String[] strings = getAll(key);
        if(strings == null) return null;
        short[] ret = new short[strings.length];
        for(int i=0;i<strings.length;i++) {
            try {
                ret[i] = Short.parseShort(strings[i]);
            } catch (NumberFormatException e) {
                Logger.error(this, "Cannot parse "+strings[i]+" : "+e, e);
                return null;
            }
        }
        return ret;
    }

    public long[] getLongArray(String key) {
        String[] strings = getAll(key);
        if(strings == null) return null;
        long[] ret = new long[strings.length];
        for(int i=0;i<strings.length;i++) {
            try {
                ret[i] = Long.parseLong(strings[i]);
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

    public boolean[] getBooleanArray(String key) {
        String[] strings = getAll(key);
        if(strings == null) return null;
        boolean[] ret = new boolean[strings.length];
        for(int i=0;i<strings.length;i++) {
            try {
                ret[i] = Boolean.valueOf(strings[i]);
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

    public void putEncoded(String key, String[] strings) {
        String[] copy = Arrays.copyOf(strings, strings.length);
        for(int i=0;i<copy.length;i++) {
            copy[i] = Base64.encodeUTF8(strings[i]);
        }
        putSingle(key, unsplit(copy));
    }

	public String getString(String key) throws FSParseException {
		String s = get(key);
		if(s == null) throw new FSParseException("No such element "+key);
		return s;
	}

	/** Set the headers. This is a list of String's that are written before the name=value pairs.
	 * Usually this is a comment (with each line starting with "#").
	 * @param headers The list of lines to precede the SimpleFieldSet by when we write it.
	 */
	public void setHeader(String... headers) {
		// FIXME LOW should really check that each line doesn't have a "\n" in it
		this.header = headers;
	}

	/** Get the headers. This is a list of String's that are written before the name=value pairs.
     * Usually this is a comment (with each line starting with "#"). */
	public String[] getHeader() {
		return this.header;
	}

	public void put(String key, String[] values) {
	    putSingle(key, unsplit(values));
	}
}
