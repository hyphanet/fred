package freenet.node;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;

import freenet.crypt.RandomSource;
import freenet.keys.CHKBlock;
import freenet.keys.CHKDecodeException;
import freenet.keys.CHKEncodeException;
import freenet.keys.ClientCHK;
import freenet.keys.ClientCHKBlock;
import freenet.keys.FreenetURI;
import freenet.support.Logger;

/**
 * @author amphibian
 * 
 * Read commands to fetch or put from stdin.
 * 
 * Execute them.
 */
public class TextModeClientInterface implements Runnable {

    RandomSource r;
    Node n;
    
    TextModeClientInterface(Node n) {
        this.n = n;
        this.r = n.random;
        new Thread(this).run();
    }
    
    /**
     * Read commands, run them
     */
    public void run() {
        System.out.println("Freenet 0.7 Trivial Node Test Interface");
        System.out.println("---------------------------------------");
        System.out.println();
        System.out.println("Enter one of the following commands:");
        System.out.println("GET:<Freenet key> - fetch a key");
        System.out.println("PUT:\n<text, until a . on a line by itself> - We will insert the document and return the key.");
        System.out.println("PUT:<text> - put a single line of text to a CHK and return the key.");
        System.out.println("PUTFILE:<filename> - put a file from disk.");
        System.out.println("GETFILE:<filename> - fetch a key and put it in a file. If the key includes a filename we will use it but we will not overwrite local files.");
        System.out.println("QUIT - exit the program");
        // Read command, and data
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        while(true) {
            String line;
            try {
                line = reader.readLine();
            } catch (IOException e) {
                System.err.println("Bye... ("+e+")");
                return;
            }
            if(line == null) line = "QUIT";
            if(line.startsWith("GET:")) {
                // Should have a key next
                String key = line.substring("GET:".length());
                while(key.length() > 0 && key.charAt(0) == ' ')
                    key = key.substring(1);
                while(key.length() > 0 && key.charAt(key.length()-1) == ' ')
                    key = key.substring(0, key.length()-2);
                Logger.normal(this, "Key: "+key);
                FreenetURI uri;
                ClientCHK chk;
                try {
                    uri = new FreenetURI(key);
                    chk = new ClientCHK(uri);
                } catch (MalformedURLException e2) {
                    System.err.println("Malformed URI: "+key+" : "+e2);
                    continue;
                }
                CHKBlock block;
                // Fetch, possibly from other node.
                block = n.getCHK(chk);
                if(block == null) {
                    System.out.println("Not found in store: "+chk.getURI());
                } else {
                    // Decode it
                    byte[] decoded;
                    try {
                        decoded = block.decode(chk);
                    } catch (CHKDecodeException e) {
                        Logger.error(this, "Cannot decode: "+e, e);
                        continue;
                    }
                    System.out.println("Decoded data:\n");
                    System.out.println(new String(decoded));
                }
            } else if(line.startsWith("GETFILE:")) {
                // Should have a key next
                String key = line.substring("GETFILE:".length());
                while(key.length() > 0 && key.charAt(0) == ' ')
                    key = key.substring(1);
                while(key.length() > 0 && key.charAt(key.length()-1) == ' ')
                    key = key.substring(0, key.length()-2);
                Logger.normal(this, "Key: "+key);
                FreenetURI uri;
                ClientCHK chk;
                try {
                    uri = new FreenetURI(key);
                    chk = new ClientCHK(uri);
                } catch (MalformedURLException e2) {
                    System.err.println("Malformed URI: "+key+" : "+e2);
                    continue;
                }
                CHKBlock block;
                // Fetch, possibly from other node.
                block = n.getCHK(chk);
                if(block == null) {
                    System.out.println("Not found in store: "+chk.getURI());
                } else {
                    // Decode it
                    byte[] decoded;
                    try {
                        decoded = block.decode(chk);
                    } catch (CHKDecodeException e) {
                        Logger.error(this, "Cannot decode: "+e, e);
                        continue;
                    }
                    // Now calculate filename
                    String fnam = uri.getDocName();
                    fnam = sanitize(fnam);
                    if(fnam.length() == 0) {
                        fnam = "freenet-download-"+System.currentTimeMillis();
                    }
                    if(new File(fnam).exists()) {
                        System.out.println("File exists already: "+fnam);
                        fnam = "freenet-"+System.currentTimeMillis()+fnam;
                    }
                    FileOutputStream fos = null;
                    try {
                        fos = new FileOutputStream(fnam);
                        fos.write(decoded);
                        fos.close();
                    } catch (IOException e) {
                        System.out.println("Could not write file: caught "+e);
                        e.printStackTrace();
                    } finally {
                        if(fos != null) try {
                            fos.close();
                        } catch (IOException e1) {
                            // Ignore
                        }
                    }
                }
            } else if(line.startsWith("QUIT")) {
                System.out.println("Goodbye.");
                System.exit(0);
            } else if(line.startsWith("PUT:")) {
                // Just insert to local store
                line = line.substring("PUT:".length());
                while(line.length() > 0 && line.charAt(0) == ' ')
                    line = line.substring(1);
                while(line.length() > 0 && line.charAt(line.length()-1) == ' ')
                    line = line.substring(0, line.length()-2);
                String content;
                if(line.length() > 0) {
                    // Single line insert
                    content = line;
                } else {
                    // Multiple line insert
                    StringBuffer sb = new StringBuffer(1000);
                    while(true) {
                        try {
                            line = reader.readLine();
                        } catch (IOException e1) {
                            System.err.println("Bye... ("+e1+")");
                            return;
                        }
                        if(line.equals(".")) break;
                        sb.append(line).append('\n');
                    }
                    content = sb.toString();
                }
                // Insert
                byte[] data = content.getBytes();
                ClientCHKBlock block;
                try {
                    block = ClientCHKBlock.encode(data);
                } catch (CHKEncodeException e) {
                    Logger.error(this, "Couldn't encode: "+e, e);
                    continue;
                }
                ClientCHK chk = block.getClientKey();
                FreenetURI uri = 
                    chk.getURI();
                n.putCHK(block);
                // Definitely interface
                System.out.println("URI: "+uri);
            } else if(line.startsWith("PUTFILE:")) {
                // Just insert to local store
                line = line.substring("PUTFILE:".length());
                while(line.length() > 0 && line.charAt(0) == ' ')
                    line = line.substring(1);
                while(line.length() > 0 && line.charAt(line.length()-1) == ' ')
                    line = line.substring(0, line.length()-2);
                File f = new File(line);
                System.out.println("Attempting to read file "+line);
                try {
                    FileInputStream fis = new FileInputStream(line);
                    DataInputStream dis = new DataInputStream(fis);
                    int length = (int)f.length();
                    byte[] data = new byte[length];
                    dis.readFully(data);
                    dis.close();
                    System.out.println("Inserting...");
                    ClientCHKBlock block;
                    try {
                        block = ClientCHKBlock.encode(data);
                    } catch (CHKEncodeException e) {
                        Logger.error(this, "Couldn't encode: "+e, e);
                        continue;
                    }
                    ClientCHK chk = block.getClientKey();
                    FreenetURI uri = 
                        chk.getURI();
                    uri = uri.setDocName(f.getName());
                    n.putCHK(block);
                    System.out.println("URI: "+uri);
                } catch (FileNotFoundException e1) {
                    System.out.println("File not found");
                } catch (IOException e) {
                    System.out.println("Could not read: "+e);
                    e.printStackTrace();
                } catch (Throwable t) {
                    System.out.println("Threw: "+t);
                    t.printStackTrace();
                }
            } else {
                
            }
        }
    }

    private String sanitize(String fnam) {
        StringBuffer sb = new StringBuffer(fnam.length());
        for(int i=0;i<fnam.length();i++) {
            char c = fnam.charAt(i);
            if(Character.isLetterOrDigit(c) || c == '-' || c == '.')
                sb.append(c);
        }
        return sb.toString();
    }
    
    

}
