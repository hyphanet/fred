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

    final Map map;
    String endMarker;
    
    public SimpleFieldSet(BufferedReader br) throws IOException {
        map = new HashMap();
        read(br);
    }

    public SimpleFieldSet(LineReader lis, int maxLineLength, int lineBufferSize) throws IOException {
    	map = new HashMap();
    	read(lis, maxLineLength, lineBufferSize);
    }
    
    /**
     * Empty constructor
     */
    public SimpleFieldSet() {
        map = new HashMap();
    }

    /**
     * Construct from a string.
     * @throws IOException if the string is too short or invalid.
     */
    public SimpleFieldSet(String content) throws IOException {
        map = new HashMap();
        StringReader sr = new StringReader(content);
        BufferedReader br = new BufferedReader(sr);
	    read(br);
    }

    /**
     * Construct from a string[].
     * @throws IOException if the string is too short or invalid.
     */
    public SimpleFieldSet(String[] content) throws IOException {
        map = new HashMap();
        String content2=new String();
        for(int i=0;i<content.length;i++)
        	content2.concat(content[i]+";");
        StringReader sr = new StringReader(content2);
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
                map.put(before, after);
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
     */
    private void read(LineReader br, int maxLength, int bufferSize) throws IOException {
        boolean firstLine = true;
        while(true) {
            String line = br.readLine(maxLength, bufferSize);
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
            	endMarker = line;
            	return;
            }
            
        }
    }

    
    public String get(String key) {
        return (String) map.get(key);
    }
    
    public String[] getAll(String key) {
    	int index = key.indexOf(';');
    	if(index == -1) return null;
    	Vector v=new Vector();
    	v.removeAllElements();
        while(index>0){
            // Mapping
            String before = key.substring(0, index);         
            String after = key.substring(index+1);
            v.addElement(before);
            key=after;
            index = key.indexOf(';');
        }
    	
    	return (String[]) v.toArray();
    }

    public void put(String key, String value) {
    	String test=null;
    	try{
    		if(map.get(key) != null)
    			test=new String((String) map.get(key));
    	}catch (Exception e){
    	}
    	if(test != null && test.equalsIgnoreCase(value)){
    		map.put(key, test+";"+value);
    	}else{
            map.put(key, value);    		
    	}
    }

    /**
     * Write the contents of the SimpleFieldSet to a Writer.
     * @param osr
     */
    public void writeTo(Writer w) throws IOException {
        Set s = map.entrySet();
        Iterator i = s.iterator();
        for(;i.hasNext();) {
            Map.Entry entry = (Map.Entry) i.next();
            String key = (String) entry.getKey();
            String value = (String) entry.getValue();
            w.write(key+"="+value+"\n");
        }
        if(endMarker != null)
        	w.write(endMarker+"\n");
        else
        	w.write("End\n");
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
}
