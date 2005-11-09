package freenet.node;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.util.Hashtable;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.crypt.RandomSource;
import freenet.io.comm.PeerParseException;
import freenet.keys.CHKBlock;
import freenet.keys.CHKDecodeException;
import freenet.keys.CHKEncodeException;
import freenet.keys.ClientCHK;
import freenet.keys.ClientCHKBlock;
import freenet.keys.FreenetURI;
import freenet.support.Bucket;
import freenet.support.BucketTools;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

/**
 * @author amphibian
 * 
 * Read commands to fetch or put from stdin.
 * 
 * Execute them.
 */
public class TextModeClientInterface implements Runnable {

    final RandomSource r;
    final Node n;
    final HighLevelSimpleClient client;
    final Hashtable streams;
    
    TextModeClientInterface(Node n) {
        this.n = n;
        client = n.makeClient();
        this.r = n.random;
        streams = new Hashtable();
        new Thread(this).start();
    }
    
    /**
     * Read commands, run them
     */
    public void run() {
        System.out.println("Freenet 0.7 Trivial Node Test Interface");
        System.out.println("---------------------------------------");
        System.out.println();
        System.out.println("Build "+Version.buildNumber);
        System.out.println("Enter one of the following commands:");
        System.out.println("GET:<Freenet key> - fetch a key");
        System.out.println("PUT:\n<text, until a . on a line by itself> - We will insert the document and return the key.");
        System.out.println("PUT:<text> - put a single line of text to a CHK and return the key.");
        System.out.println("PUTFILE:<filename> - put a file from disk.");
        System.out.println("GETFILE:<filename> - fetch a key and put it in a file. If the key includes a filename we will use it but we will not overwrite local files.");
        System.out.println("PUBLISH:<name> - create a publish/subscribe stream called <name>");
        System.out.println("PUSH:<name>:<text> - publish a single line of text to the stream named");
        System.out.println("SUBSCRIBE:<key> - subscribe to a publish/subscribe stream by key");
        System.out.println("CONNECT:<filename> - connect to a node from its ref in a file.");
        System.out.println("CONNECT:\n<noderef including an End on a line by itself> - enter a noderef directly.");
        System.out.println("NAME:<new node name> - change the node's name.");
        System.out.println("SUBFILE:<filename> - append all data received from subscriptions to a file, rather than sending it to stdout.");
        System.out.println("SAY:<text> - send text to the last created/pushed stream");
        System.out.println("STATUS - display some status information on the node including its reference and connections.");
        System.out.println("QUIT - exit the program");
        // Read command, and data
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        while(true) {
            try {
                processLine(reader);
            } catch (Throwable t) {
                Logger.error(this, "Caught "+t, t);
                System.out.println("Caught: "+t);
                t.printStackTrace();
            }
        }
    }

    /**
     * Process a single command.
     * @throws IOException If we could not write the data to stdout.
     */
    private void processLine(BufferedReader reader) throws IOException {
        String line;
        try {
            line = reader.readLine();
        } catch (IOException e) {
            System.err.println("Bye... ("+e+")");
            return;
        }
        if(line == null) line = "QUIT";
        String uline = line.toUpperCase();
        Logger.minor(this, "Command: "+line);
        if(uline.startsWith("GET:")) {
            // Should have a key next
            String key = line.substring("GET:".length());
            while(key.length() > 0 && key.charAt(0) == ' ')
                key = key.substring(1);
            while(key.length() > 0 && key.charAt(key.length()-1) == ' ')
                key = key.substring(0, key.length()-2);
            Logger.normal(this, "Key: "+key);
            FreenetURI uri;
            try {
                uri = new FreenetURI(key);
                Logger.normal(this, "Key: "+uri);
            } catch (MalformedURLException e2) {
                System.out.println("Malformed URI: "+key+" : "+e2);
                return;
            }
            try {
				FetchResult result = client.fetch(uri);
				System.out.println("Data:\n");
				Bucket data = result.asBucket();
				BucketTools.copyTo(data, System.out, Long.MAX_VALUE);
				System.out.println();
			} catch (FetchException e) {
				System.out.println("Error: "+e.getMessage());
			}
        } else if(uline.startsWith("GETFILE:")) {
            // Should have a key next
            String key = line.substring("GETFILE:".length());
            while(key.length() > 0 && key.charAt(0) == ' ')
                key = key.substring(1);
            while(key.length() > 0 && key.charAt(key.length()-1) == ' ')
                key = key.substring(0, key.length()-2);
            Logger.normal(this, "Key: "+key);
            FreenetURI uri;
            try {
                uri = new FreenetURI(key);
            } catch (MalformedURLException e2) {
                System.out.println("Malformed URI: "+key+" : "+e2);
                return;
            }
            try {
				FetchResult result = client.fetch(uri);
				Bucket data = result.asBucket();
                // Now calculate filename
                String fnam = uri.getDocName();
                fnam = sanitize(fnam);
                if(fnam.length() == 0) {
                    fnam = "freenet-download-"+System.currentTimeMillis();
                }
                if(new File(fnam).exists()) {
                    System.out.println("File exists already: "+fnam);
                    fnam = "freenet-"+System.currentTimeMillis()+"-"+fnam;
                }
                FileOutputStream fos = null;
                try {
                    fos = new FileOutputStream(fnam);
                    BucketTools.copyTo(data, fos, Long.MAX_VALUE);
                    fos.close();
                    System.out.println("Written to "+fnam);
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
			} catch (FetchException e) {
				System.out.println("Error: "+e.getMessage());
			}
        } else if(uline.startsWith("QUIT")) {
            System.out.println("Goodbye.");
            System.exit(0);
        } else if(uline.startsWith("PUT:")) {
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
                content = readLines(reader, false);
            }
            // Insert
            byte[] data = content.getBytes();
            ClientCHKBlock block;
            try {
                block = ClientCHKBlock.encode(data, false, false, (short)-1);
            } catch (CHKEncodeException e) {
                Logger.error(this, "Couldn't encode: "+e, e);
                return;
            }
            ClientCHK chk = block.getClientKey();
            FreenetURI uri = 
                chk.getURI();
            try {
				n.putCHK(block);
			} catch (LowLevelPutException e) {
				System.err.println("Error: "+e.getMessage());
			}
            // Definitely interface
            System.out.println("URI: "+uri);
        } else if(uline.startsWith("PUTFILE:")) {
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
                    block = ClientCHKBlock.encode(data, false, false, (short)-1);
                } catch (CHKEncodeException e) {
                    System.out.println("Couldn't encode: "+e.getMessage());
                    Logger.error(this, "Couldn't encode: "+e, e);
                    return;
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
			} catch (LowLevelPutException e) {
				System.err.println("Error: "+e.getMessage());
            } catch (Throwable t) {
                System.out.println("Threw: "+t);
                t.printStackTrace();
            }
        } else if(uline.startsWith("STATUS")) {
            SimpleFieldSet fs = n.exportFieldSet();
            System.out.println(fs.toString());
            System.out.println();
            System.out.println(n.getStatus());
        } else if(uline.startsWith("CONNECT:")) {
            String key = line.substring("CONNECT:".length());
            while(key.length() > 0 && key.charAt(0) == ' ')
                key = key.substring(1);
            while(key.length() > 0 && key.charAt(key.length()-1) == ' ')
                key = key.substring(0, key.length()-2);
            if(key.length() > 0) {
                // Filename
                System.out.println("Trying to connect to noderef in "+key);
                File f = new File(key);
                System.out.println("Attempting to read file "+key);
                try {
                    FileInputStream fis = new FileInputStream(key);
                    DataInputStream dis = new DataInputStream(fis);
                    int length = (int)f.length();
                    byte[] data = new byte[length];
                    dis.readFully(data);
                    dis.close();
                    connect(new String(data));
                } catch (IOException e) {
                    System.err.println("Could not read file: "+e);
                    e.printStackTrace(System.err);
                }
            } else {
                String content = readLines(reader, true);
                if(content == null) return;
                if(content.equals("")) return;
                connect(content);
            }
        } else if(uline.startsWith("NAME:")) {
            System.out.println("Node name currently: "+n.myName);
            String key = line.substring("NAME:".length());
            while(key.length() > 0 && key.charAt(0) == ' ')
                key = key.substring(1);
            while(key.length() > 0 && key.charAt(key.length()-1) == ' ')
                key = key.substring(0, key.length()-2);
            System.out.println("New name: "+key);
            n.setName(key);
        } else {
            
        }
    }

    /**
     * @return A block of text, input from stdin, ending with a
     * . on a line by itself. Does some mangling for a fieldset if 
     * isFieldSet. 
     */
    private String readLines(BufferedReader reader, boolean isFieldSet) {
        StringBuffer sb = new StringBuffer(1000);
        boolean breakflag = false;
        while(true) {
            String line;
            try {
                line = reader.readLine();
                if(line == null) throw new EOFException();
            } catch (IOException e1) {
                System.err.println("Bye... ("+e1+")");
                return null;
            }
            if((!isFieldSet) && line.equals(".")) break;
            if(isFieldSet) {
                // Mangling
                // First trim
                line = line.trim();
                if(line.equals("End")) {
                    breakflag = true;
                } else {
                    if(line.endsWith("End") && 
                            Character.isWhitespace(line.charAt(line.length()-("End".length()+1)))) {
                        line = "End";
                        breakflag = true;
                    } else {
                        int idx = line.indexOf('=');
                        if(idx < 0) {
                            System.err.println("No = and no End in line: "+line);
                            return "";
                        } else {
                            if(idx > 0) {
                                String after;
                                if(idx == line.length()-1)
                                    after = "";
                                else
                                    after = line.substring(idx+1);
                                String before = line.substring(0, idx);
                                before = before.trim();
                                int x = 0;
                                for(int j=before.length()-1;j>=0;j--) {
                                    char c = before.charAt(j);
                                    if(c == '.' || Character.isLetterOrDigit(c)) {
                                        // Valid character for field
                                    } else {
                                        x=j+1;
                                        break;
                                    }
                                }
                                before = before.substring(x);
                                line = before + '=' + after;
                                //System.out.println(line);
                            } else {
                                System.err.println("Invalid empty field name");
                                breakflag = true;
                            }
                        }
                    }
                }
            }
            sb.append(line).append('\n');
            if(breakflag) break;
        }
        return sb.toString();
    }

    /**
     * Connect to a node, given its reference.
     */
    private void connect(String content) {
        SimpleFieldSet fs;
        System.out.println("Connecting to:\n"+content);
        try {
            fs = new SimpleFieldSet(content);
        } catch (IOException e) {
            System.err.println("Did not parse: "+e);
            e.printStackTrace();
            return;
        }
        PeerNode pn;
        try {
            pn = new PeerNode(fs, n);
        } catch (FSParseException e1) {
            System.err.println("Did not parse: "+e1.getMessage());
            return;
        } catch (PeerParseException e1) {
            System.err.println("Did not parse: "+e1.getMessage());
            return;
        }
        if(n.peers.addPeer(pn))
            System.out.println("Added peer: "+pn);
        n.peers.writePeers();
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
