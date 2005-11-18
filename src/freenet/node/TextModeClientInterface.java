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
import java.net.MalformedURLException;
import java.util.Hashtable;

import freenet.client.ClientMetadata;
import freenet.client.DefaultMIMETypes;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertBlock;
import freenet.client.InserterException;
import freenet.client.events.EventDumper;
import freenet.crypt.RandomSource;
import freenet.io.comm.PeerParseException;
import freenet.keys.CHKEncodeException;
import freenet.keys.ClientCHK;
import freenet.keys.ClientCHKBlock;
import freenet.keys.FreenetURI;
import freenet.support.ArrayBucket;
import freenet.support.Bucket;
import freenet.support.BucketTools;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.io.FileBucket;

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
    final RequestStarterClient requestStarterClient;
    final RequestStarterClient insertStarterClient;
    
    TextModeClientInterface(Node n) {
        this.n = n;
        client = n.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS, (short)0);
        client.addGlobalHook(new EventDumper());
        this.r = n.random;
        streams = new Hashtable();
        new Thread(this, "Text mode client interface").start();
        this.requestStarterClient = n.makeStarterClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS, (short)0, false);
        this.insertStarterClient = n.makeStarterClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS, (short)0, true);
    }
    
    /**
     * Read commands, run them
     */
    public void run() {
    	printHeader();
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

    private void printHeader() {
        System.out.println("Freenet 0.7 Trivial Node Test Interface");
        System.out.println("---------------------------------------");
        System.out.println();
        System.out.println("Build "+Version.buildNumber);
        System.out.println("Enter one of the following commands:");
        System.out.println("GET:<Freenet key> - Fetch a key");
        System.out.println("PUT:\n<text, until a . on a line by itself> - Insert the document and return the key.");
        System.out.println("PUT:<text> - Put a single line of text to a CHK and return the key.");
        System.out.println("GETCHK:\n<text, until a . on a line by itself> - Get the key that would be returned if the document was inserted.");
        System.out.println("GETCHK:<text> - Get the key that would be returned if the line was inserted.");
        System.out.println("PUTFILE:<filename> - Put a file from disk.");
        System.out.println("GETFILE:<filename> - Fetch a key and put it in a file. If the key includes a filename we will use it but we will not overwrite local files.");
        System.out.println("GETCHKFILE:<filename> - Get the key that would be returned if we inserted the file.");
//        System.out.println("PUBLISH:<name> - create a publish/subscribe stream called <name>");
//        System.out.println("PUSH:<name>:<text> - publish a single line of text to the stream named");
//        System.out.println("SUBSCRIBE:<key> - subscribe to a publish/subscribe stream by key");
        System.out.println("CONNECT:<filename> - connect to a node from its ref in a file.");
        System.out.println("CONNECT:\n<noderef including an End on a line by itself> - enter a noderef directly.");
        System.out.println("NAME:<new node name> - change the node's name.");
//        System.out.println("SUBFILE:<filename> - append all data received from subscriptions to a file, rather than sending it to stdout.");
//        System.out.println("SAY:<text> - send text to the last created/pushed stream");
        System.out.println("STATUS - display some status information on the node including its reference and connections.");
        System.out.println("QUIT - exit the program");
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
        boolean getCHKOnly = false;
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
				ClientMetadata cm = result.getMetadata();
				System.out.println("Content MIME type: "+cm.getMIMEType());
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
            	long startTime = System.currentTimeMillis();
				FetchResult result = client.fetch(uri);
				ClientMetadata cm = result.getMetadata();
				System.out.println("Content MIME type: "+cm.getMIMEType());
				Bucket data = result.asBucket();
                // Now calculate filename
                String fnam = uri.getDocName();
                fnam = sanitize(fnam);
                if(fnam.length() == 0) {
                    fnam = "freenet-download-"+System.currentTimeMillis();
                    String ext = DefaultMIMETypes.getExtension(cm.getMIMEType());
                    if(ext != null && !ext.equals(""))
                    	fnam += "." + ext;
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
                long endTime = System.currentTimeMillis();
                long sz = data.size();
                double rate = 1000.0 * sz / (endTime-startTime);
                System.out.println("Download rate: "+rate+" bytes / second");
			} catch (FetchException e) {
				System.out.println("Error: "+e.getMessage());
			}
        } else if(uline.startsWith("QUIT")) {
            System.out.println("Goodbye.");
            System.exit(0);
        } else if(uline.startsWith("PUT:") || (getCHKOnly = uline.startsWith("GETCHK:"))) {
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
            
            InsertBlock block = new InsertBlock(new ArrayBucket(data), null, FreenetURI.EMPTY_CHK_URI);

            FreenetURI uri;
            try {
            	uri = client.insert(block, getCHKOnly);
            } catch (InserterException e) {
            	System.out.println("Error: "+e.getMessage());
            	if(e.uri != null)
            		System.out.println("URI would have been: "+e.uri);
            	if(e.mode == e.FATAL_ERRORS_IN_BLOCKS || e.mode == e.TOO_MANY_RETRIES_IN_BLOCKS) {
            		System.out.println("Splitfile-specific error:\n"+e.errorCodes.toVerboseString());
            	}
            	return;
            }
            
            System.out.println("URI: "+uri);
        } else if(uline.startsWith("PUTFILE:") || (getCHKOnly = uline.startsWith("GETCHKFILE:"))) {
            // Just insert to local store
            line = line.substring("PUTFILE:".length());
            while(line.length() > 0 && line.charAt(0) == ' ')
                line = line.substring(1);
            while(line.length() > 0 && line.charAt(line.length()-1) == ' ')
                line = line.substring(0, line.length()-2);
            File f = new File(line);
            System.out.println("Attempting to read file "+line);
            long startTime = System.currentTimeMillis();
            try {
            	if(!(f.exists() && f.canRead())) {
            		throw new FileNotFoundException();
            	}
            	
            	// Guess MIME type
            	String mimeType = DefaultMIMETypes.guessMIMEType(line);
            	System.out.println("Using MIME type: "+mimeType);
            	
            	FileBucket fb = new FileBucket(f, true, false, false);
            	InsertBlock block = new InsertBlock(fb, new ClientMetadata(mimeType), FreenetURI.EMPTY_CHK_URI);

            	startTime = System.currentTimeMillis();
            	FreenetURI uri = client.insert(block, getCHKOnly);
            	
            	// FIXME depends on CHK's still being renamable
                //uri = uri.setDocName(f.getName());
            	
                System.out.println("URI: "+uri);
            	long endTime = System.currentTimeMillis();
                long sz = f.length();
                double rate = 1000.0 * sz / (endTime-startTime);
                System.out.println("Upload rate: "+rate+" bytes / second");
            } catch (FileNotFoundException e1) {
                System.out.println("File not found");
            } catch (InserterException e) {
            	System.out.println("Finished insert but: "+e.getMessage());
            	if(e.uri != null) {
            		System.out.println("URI would have been: "+e.uri);
                	long endTime = System.currentTimeMillis();
                    long sz = f.length();
                    double rate = 1000.0 * sz / (endTime-startTime);
                    System.out.println("Upload rate: "+rate+" bytes / second");
            	}
            } catch (Throwable t) {
                System.out.println("Insert threw: "+t);
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
        	if(uline.length() > 0)
        		printHeader();
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
