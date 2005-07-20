package freenet.node;

import java.io.BufferedReader;
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
            } else {
                
            }
        }
    }
    
    

}
