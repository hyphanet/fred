package freenet.support;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * @author amphibian
 * 
 * Very very simple FieldSet type thing, which uses the standard
 * Java facilities.
 */
public class SimpleFieldSet {

    final Map map;
    
    public SimpleFieldSet(BufferedReader br) throws IOException {
        map = new HashMap();
        read(br);
    }

    /**
     * Read from disk
     * Format:
     * <blah>=<blah>
     * <blah>=<blah>
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
                map.put(before, after);
            } else {
                if(line.equals("End")) return;
                throw new IOException("Unknown end-marker: "+line);
            }
            
        }
    }

    /**
     * @param string
     * @return
     */
    public String get(String key) {
        return (String) map.get(key);
    }
    
}
