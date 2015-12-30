package freenet.pluginmanager;

import java.util.Arrays;
import java.util.Random;

import freenet.node.FSParseException;
import freenet.support.IllegalBase64Exception;
import freenet.support.SimpleFieldSet;
import junit.framework.TestCase;

public class PluginStoreTest extends TestCase {
    
    private void check(PluginStore store) throws IllegalBase64Exception, FSParseException {
        SimpleFieldSet fs = store.exportStoreAsSFS();
        PluginStore copyStore = new PluginStore(fs);
        assertEquals(fs.toOrderedString(), copyStore.exportStoreAsSFS().toOrderedString());
    }

    /** These are not allowed in SFS except base64 encoded when the always allow base64 flag is on.
     * So they ARE allowed in PluginStore's. */
    private String invalidCharsForSFS = "\r\n"+SimpleFieldSet.KEYVALUE_SEPARATOR_CHAR+
        SimpleFieldSet.MULTI_LEVEL_CHAR+SimpleFieldSet.MULTI_VALUE_CHAR;
    
    public void testStringsWithInvalidChars() throws IllegalBase64Exception, FSParseException {
        PluginStore store = new PluginStore();
        for(int i=0;i<invalidCharsForSFS.length();i++) {
            char c = invalidCharsForSFS.charAt(i);
            store.strings.put(String.valueOf(c), String.valueOf(c));
        }
        check(store);
    }
    
    public void testRandom() throws IllegalBase64Exception, FSParseException {
        Random r = new Random(1234);
        PluginStore store = new PluginStore();
        addRandomContent(store, r);
        check(store);
    }
    
    private void addRandomContent(PluginStore store, Random r) {
        for(int i=0;i<20;i++) {
            store.booleans.put(randomKey(r), r.nextBoolean());
        }
        for(int i=0;i<20;i++) {
            boolean[] list = new boolean[r.nextInt(10)];
            for(int j=0;j<list.length;j++) list[j] = r.nextBoolean();
            store.booleansArrays.put(randomKey(r), list);
        }
        for(int i=0;i<20;i++) {
            store.integers.put(randomKey(r), r.nextInt());
        }
        for(int i=0;i<20;i++) {
            int[] list = new int[r.nextInt(10)];
            for(int j=0;j<list.length;j++) list[j] = r.nextInt();
            store.integersArrays.put(randomKey(r), list);
        }
        for(int i=0;i<20;i++) {
            store.longs.put(randomKey(r), r.nextLong());
        }
        for(int i=0;i<20;i++) {
            long[] list = new long[r.nextInt(10)];
            for(int j=0;j<list.length;j++) list[j] = r.nextLong();
            store.longsArrays.put(randomKey(r), list);
        }
        for(int i=0;i<20;i++) {
            store.shorts.put(randomKey(r), (short)r.nextInt());
        }
        for(int i=0;i<20;i++) {
            short[] list = new short[r.nextInt(10)];
            for(int j=0;j<list.length;j++) list[j] = (short)r.nextInt();
            store.shortsArrays.put(randomKey(r), list);
        }
        for(int i=0;i<20;i++) {
            store.bytes.put(randomKey(r), (byte)r.nextInt());
        }
        for(int i=0;i<20;i++) {
            byte[] list = new byte[r.nextInt(10)];
            r.nextBytes(list);
            store.bytesArrays.put(randomKey(r), list);
        }
        for(int i=0;i<20;i++) {
            store.strings.put(randomKey(r), randomKey(r));
        }
        for(int i=0;i<20;i++) {
            String[] list = new String[r.nextInt(10)];
            for(int j=0;j<list.length;j++) list[j] = randomKey(r);
            store.stringsArrays.put(randomKey(r), list);
        }
    }

    private String randomKey(Random r) {
        int len = r.nextInt(20);
        char[] c = new char[len];
        for(int i=0;i<len;i++) {
            c[i] = randomChar(r);
        }
        return new String(c);
    }

    private char randomChar(Random r) {
        int mode = r.nextInt(3);
        if(mode == 0)
            return (char)(32 + r.nextInt(95));
        else if(mode == 1)
            return invalidCharsForSFS.charAt(r.nextInt(invalidCharsForSFS.length()));
        else {
            while(true) {
                char ch = (char) (128+r.nextInt(0xF000-128));
                if(Character.isDefined(ch) && (!Character.isHighSurrogate(ch)) &&
                        (!Character.isISOControl(ch)) && !Character.isSupplementaryCodePoint(ch)) {
                    return ch;
                }
            }
        }
    }
    
    public void testWriteStringArrays() throws IllegalBase64Exception, FSParseException {
        String key = "test";
        String[] value = new String[] { "tag1", "tag2" };
        PluginStore store = new PluginStore();
        store.stringsArrays.put(key, value);
        SimpleFieldSet fs = store.exportStoreAsSFS();
        PluginStore copy = new PluginStore(fs);
        assertTrue(Arrays.equals(copy.stringsArrays.get(key), value));
    }

}
