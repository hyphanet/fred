package freenet.support;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

import freenet.support.io.LineReader;

/**
 * @author amphibian
 * 
 * Very very simple FieldSet type thing, which uses the standard
 * Java facilities.
 */
public class SimpleFieldSet {

    private final Map map;
    private String endMarker;
    private final boolean multiLevel;
    static public final char MULTI_LEVEL_CHAR = '.';
    
    public SimpleFieldSet(BufferedReader br, boolean multiLevel) throws IOException {
        map = new HashMap();
        this.multiLevel = multiLevel;
        read(br);
    }

    public SimpleFieldSet(LineReader lis, int maxLineLength, int lineBufferSize, boolean multiLevel, boolean tolerant, boolean utf8OrIso88591) throws IOException {
    	map = new HashMap();
    	this.multiLevel = multiLevel;
    	read(lis, maxLineLength, lineBufferSize, tolerant, utf8OrIso88591);
    }
    
    /**
     * Empty constructor
     */
    public SimpleFieldSet(boolean multiLevel) {
        map = new HashMap();
        this.multiLevel = multiLevel;
    }

    /**
     * Construct from a string.
     * @throws IOException if the string is too short or invalid.
     */
    public SimpleFieldSet(String content, boolean multiLevel) throws IOException {
        map = new HashMap();
        this.multiLevel = multiLevel;
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
            if(line.length() == 0 && tolerant) continue; // ignore
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
    
    public String get(String key) {
    	if(multiLevel) {
    		int idx = key.indexOf(MULTI_LEVEL_CHAR);
    		if(idx == -1)
    			return (String) map.get(key);
    		else if(idx == 0)
    			return null;
    		else {
    			String before = key.substring(0, idx);
    			String after = key.substring(idx+1);
    			SimpleFieldSet fs = (SimpleFieldSet) (map.get(before));
    			if(fs == null) return null;
    			return fs.get(after);
    		}
    	} else {
    		return (String) map.get(key);
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

	public void put(String key, String value) {
		int idx;
		if(value == null) return;
		if((!multiLevel) || (idx = key.indexOf(MULTI_LEVEL_CHAR)) == -1) {
			String x = (String) map.get(key);
			
			if(x == null) {
				map.put(key, value);
			} else {
				map.put(key, ((String)map.get(key))+";"+value);
			}
		} else {
			String before = key.substring(0, idx);
			String after = key.substring(idx+1);
			SimpleFieldSet fs = (SimpleFieldSet) (map.get(before));
			if(fs == null) {
				fs = new SimpleFieldSet(true);
				map.put(before, fs);
			}
			fs.put(after, value);
		}
    }

    /**
     * Write the contents of the SimpleFieldSet to a Writer.
     * @param osr
     */
	public void writeTo(Writer w) throws IOException {
		writeTo(w, "", false);
	}
	
    void writeTo(Writer w, String prefix, boolean noEndMarker) throws IOException {
        Set s = map.entrySet();
        Iterator i = s.iterator();
        for(;i.hasNext();) {
            Map.Entry entry = (Map.Entry) i.next();
            String key = (String) entry.getKey();
            Object v = entry.getValue();
            if(v instanceof String) {
                String value = (String) v;
                w.write(prefix+key+"="+value+"\n");
            } else {
            	SimpleFieldSet sfs = (SimpleFieldSet) v;
            	if(sfs == null) throw new NullPointerException();
            	sfs.writeTo(w, prefix+key+MULTI_LEVEL_CHAR, true);
            }
        }
        if(!noEndMarker) {
        	if(endMarker != null)
        		w.write(endMarker+"\n");
        	else
        		w.write("End\n");
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
    
    public String getEndMarker() {
    	return endMarker;
    }
    
    public void setEndMarker(String s) {
    	endMarker = s;
    }

	public SimpleFieldSet subset(String key) {
		if(!multiLevel)
			throw new IllegalArgumentException("Not multi-level!");
		int idx = key.indexOf(MULTI_LEVEL_CHAR);
		if(idx == -1)
			return (SimpleFieldSet) map.get(key);
		String before = key.substring(0, idx);
		String after = key.substring(idx+1);
		SimpleFieldSet fs = (SimpleFieldSet) map.get(before);
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
    	
    	final Iterator mapIterator;
    	KeyIterator subIterator;
    	String prefix;
    	
    	public KeyIterator(String prefix) {
    		mapIterator = map.keySet().iterator();
    		this.prefix = prefix;
    	}

		public boolean hasNext() {
			if(subIterator != null && subIterator.hasNext()) return true;
			if(subIterator != null) subIterator = null;
			return mapIterator.hasNext();
		}

		public Object next() {
			while(true) { // tail-recurse so we get infinite loop instead of OOM in case of a loop...
				if(subIterator != null && subIterator.hasNext()) {
					return subIterator.next();
				}
				if(subIterator != null) subIterator = null;
				if(mapIterator.hasNext()) {
					String key = (String) mapIterator.next();
					Object value = map.get(key);
					if(value instanceof String)
						return prefix + MULTI_LEVEL_CHAR + key;
					else {
						SimpleFieldSet fs = (SimpleFieldSet) value;
						subIterator = fs.keyIterator((prefix.length() == 0) ? key : (prefix+MULTI_LEVEL_CHAR+key));
						continue;
					}
				}
				return null;
			}
		}

		public void remove() {
			throw new UnsupportedOperationException();
		}

	}

	public void put(String key, SimpleFieldSet fs) {
		if(fs.isEmpty())
			throw new IllegalArgumentException("Empty");
		if(!multiLevel)
			throw new IllegalArgumentException("Not multi-level");
		if(!fs.multiLevel)
			throw new IllegalArgumentException("Argument not multi-level");
		if(map.containsKey(key))
			throw new IllegalArgumentException("Already contains "+key+" but trying to add a SimpleFieldSet!");
		map.put(key, fs);
	}

	public void remove(String key) {
		int idx;
		if((!multiLevel) || (idx = key.indexOf(MULTI_LEVEL_CHAR)) == -1) {
			map.remove(key);
		} else {
			String before = key.substring(0, idx);
			String after = key.substring(idx+1);
			SimpleFieldSet fs = (SimpleFieldSet) (map.get(before));
			if(fs == null) {
				return;
			}
			fs.remove(after);
			if(fs.isEmpty())
				map.remove(before);
		}
	}

	/** Is this SimpleFieldSet empty? */
	public boolean isEmpty() {
		return map.isEmpty();
	}

	public Iterator directSubsetNameIterator() {
		return new DirectSubsetNameIterator();
	}

	public class DirectSubsetNameIterator implements Iterator {

		Iterator mapIterator;
		String nextName;
		
		DirectSubsetNameIterator() {
			mapIterator = map.keySet().iterator();
			fetchNext();
		}
		
		public boolean hasNext() {
			return nextName != null;
		}

		public Object next() {
			String next = nextName;
			fetchNext();
			return next;
		}

		public void remove() {
			throw new UnsupportedOperationException();
		}
		
		void fetchNext() {
			// Maybe more efficient with Entry's???
			while(mapIterator.hasNext()) {
				String name = (String) mapIterator.next();
				Object target = map.get(name);
				if(target instanceof SimpleFieldSet) {
					nextName = name;
					return;
				}
			}
			nextName = null;
		}
	}

	public String[] namesOfDirectSubsets() {
		Iterator i = new DirectSubsetNameIterator();
		Vector v = new Vector();
		while(i.hasNext()) v.add(i.next());
		return (String[]) v.toArray(new String[v.size()]);
	}
	

}
