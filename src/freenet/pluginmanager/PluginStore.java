/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import java.util.HashMap;
import java.util.Map;

import freenet.node.FSParseException;
import freenet.support.Base64;
import freenet.support.IllegalBase64Exception;
import freenet.support.SimpleFieldSet;

/**
 * This is a PluginStore. Plugins can use that to store all kinds of primary
 * data types with as many recursion level as needed.
 * @author Artefact2
 */
public class PluginStore {
	public final HashMap<String, PluginStore> subStores = new HashMap<String, PluginStore>();
	public final HashMap<String, Long> longs = new HashMap<String, Long>();
	public final HashMap<String, long[]> longsArrays = new HashMap<String, long[]>();
	public final HashMap<String, Integer> integers = new HashMap<String, Integer>();
	public final HashMap<String, int[]> integersArrays = new HashMap<String, int[]>();
	public final HashMap<String, Short> shorts = new HashMap<String, Short>();
	public final HashMap<String, short[]> shortsArrays = new HashMap<String, short[]>();
	public final HashMap<String, Boolean> booleans = new HashMap<String, Boolean>();
	public final HashMap<String, boolean[]> booleansArrays = new HashMap<String, boolean[]>();
	public final HashMap<String, Byte> bytes = new HashMap<String, Byte>();
	public final HashMap<String, byte[]> bytesArrays = new HashMap<String, byte[]>();
	public final HashMap<String, String> strings = new HashMap<String, String>();
	public final HashMap<String, String[]> stringsArrays = new HashMap<String, String[]>();
	
	public PluginStore() {
	    // Default constructor. See below for constructor from SFS.
	}
	
	public SimpleFieldSet exportStoreAsSFS() {
	    SimpleFieldSet fs = new SimpleFieldSet(true, true);
	    for(Map.Entry<String, PluginStore> entry : subStores.entrySet()) {
	        fs.put("substore."+encode(entry.getKey()), entry.getValue().exportStoreAsSFS());
	    }
	    for(Map.Entry<String, Long> entry : longs.entrySet()) {
	        fs.put("long."+encode(entry.getKey()), entry.getValue());
	    }
	    for(Map.Entry<String, long[]> entry : longsArrays.entrySet()) {
	        fs.put("longs."+encode(entry.getKey()), entry.getValue());
	    }
	    for(Map.Entry<String, Integer> entry : integers.entrySet()) {
	        fs.put("integer."+encode(entry.getKey()), entry.getValue());
	    }
	    for(Map.Entry<String, int[]> entry : integersArrays.entrySet()) {
	        fs.put("integers."+encode(entry.getKey()), entry.getValue());
	    }
	    for(Map.Entry<String, Short> entry : shorts.entrySet()) {
	        fs.put("short."+encode(entry.getKey()), entry.getValue());
	    }
	    for(Map.Entry<String, short[]> entry : shortsArrays.entrySet()) {
	        fs.put("shorts."+encode(entry.getKey()), entry.getValue());
	    }
	    for(Map.Entry<String, Byte> entry : bytes.entrySet()) {
	        fs.put("byte."+encode(entry.getKey()), entry.getValue());
	    }
	    for(Map.Entry<String, byte[]> entry : bytesArrays.entrySet()) {
	        fs.put("bytes."+encode(entry.getKey()), entry.getValue());
	    }
        for(Map.Entry<String, Boolean> entry : booleans.entrySet()) {
            fs.put("boolean."+encode(entry.getKey()), entry.getValue());
        }
        for(Map.Entry<String, boolean[]> entry : booleansArrays.entrySet()) {
            fs.put("booleans."+encode(entry.getKey()), entry.getValue());
        }
	    for(Map.Entry<String, String> entry : strings.entrySet()) {
	        fs.putSingle("string."+encode(entry.getKey()), entry.getValue());
	    }
	    for(Map.Entry<String, String[]> entry : stringsArrays.entrySet()) {
	        fs.putEncoded("strings."+encode(entry.getKey()), entry.getValue());
	    }
	    return fs;
	}
	
    public PluginStore(SimpleFieldSet sfs) throws IllegalBase64Exception, FSParseException {
        SimpleFieldSet group = sfs.subset("substore");
        if(group != null) {
            for(Map.Entry<String, SimpleFieldSet> entry : group.directSubsets().entrySet()) {
                subStores.put(decode(entry.getKey()), new PluginStore(entry.getValue()));
            }
        }
        group = sfs.subset("long");
        if(group != null) {
            for(String s : group.directKeys()) {
                longs.put(decode(s), group.getLong(s));
            }
        }
        group = sfs.subset("longs");
        if(group != null) {
            for(String s : group.directKeys()) {
                longsArrays.put(decode(s), group.getLongArray(s));
            }
        }
        group = sfs.subset("integer");
        if(group != null) {
            for(String s : group.directKeys()) {
                integers.put(decode(s), group.getInt(s));
            }
        }
        group = sfs.subset("integers");
        if(group != null) {
            for(String s : group.directKeys()) {
                integersArrays.put(decode(s), group.getIntArray(s));
            }
        }
        group = sfs.subset("short");
        if(group != null) {
            for(String s : group.directKeys()) {
                shorts.put(decode(s), group.getShort(s));
            }
        }
        group = sfs.subset("shorts");
        if(group != null) {
            for(String s : group.directKeys()) {
                shortsArrays.put(decode(s), group.getShortArray(s));
            }
        }
        group = sfs.subset("boolean");
        if(group != null) {
            for(String s : group.directKeys()) {
                booleans.put(decode(s), group.getBoolean(s));
            }
        }
        group = sfs.subset("booleans");
        if(group != null) {
            for(String s : group.directKeys()) {
                booleansArrays.put(decode(s), group.getBooleanArray(s));
            }
        }
        group = sfs.subset("byte");
        if(group != null) {
            for(String s : group.directKeys()) {
                bytes.put(decode(s), group.getByte(s));
            }
        }
        group = sfs.subset("bytes");
        if(group != null) {
            for(String s : group.directKeys()) {
                bytesArrays.put(decode(s), group.getByteArray(s));
            }
        }
        group = sfs.subset("string");
        if(group != null) {
            for(String s : group.directKeys()) {
                strings.put(decode(s), group.get(s));
            }
        }
        group = sfs.subset("strings");
        if(group != null) {
            for(String s : group.directKeys()) {
                stringsArrays.put(decode(s), group.getAllEncoded(s));
            }
        }
    }

	private static final String encode(String s) {
	    return Base64.encodeUTF8(s);
	}
	
	private static final String decode(String s) throws IllegalBase64Exception {
	    return Base64.decodeUTF8(s);
	}

}
