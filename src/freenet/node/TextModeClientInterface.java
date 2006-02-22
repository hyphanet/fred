package freenet.node;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;

import freenet.client.ClientMetadata;
import freenet.client.DefaultMIMETypes;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertBlock;
import freenet.client.InserterException;
import freenet.client.Metadata;
import freenet.client.events.EventDumper;
import freenet.config.BooleanCallback;
import freenet.config.Config;
import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.crypt.RandomSource;
import freenet.io.comm.Peer;
import freenet.io.comm.PeerParseException;
import freenet.keys.FreenetURI;
import freenet.keys.InsertableClientSSK;
import freenet.support.ArrayBucket;
import freenet.support.Bucket;
import freenet.support.BucketTools;
import freenet.support.HexUtil;
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
    final File downloadsDir;
    
    TextModeClientInterface(Node n) {
        this.n = n;
        client = n.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS);
        client.addGlobalHook(new EventDumper());
        this.r = n.random;
        streams = new Hashtable();
        new Thread(this, "Text mode client interface").start();
        this.downloadsDir = n.downloadDir;
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
        System.out.println("Build "+Version.buildNumber());
        System.out.println("Enter one of the following commands:");
        System.out.println("GET:<Freenet key> - Fetch a key");
        System.out.println("PUT:\n<text, until a . on a line by itself> - Insert the document and return the key.");
        System.out.println("PUT:<text> - Put a single line of text to a CHK and return the key.");
        System.out.println("GETCHK:\n<text, until a . on a line by itself> - Get the key that would be returned if the document was inserted.");
        System.out.println("GETCHK:<text> - Get the key that would be returned if the line was inserted.");
        System.out.println("PUTFILE:<filename> - Put a file from disk.");
        System.out.println("GETFILE:<filename> - Fetch a key and put it in a file. If the key includes a filename we will use it but we will not overwrite local files.");
        System.out.println("GETCHKFILE:<filename> - Get the key that would be returned if we inserted the file.");
        System.out.println("PUTDIR:<path>[#<defaultfile>] - Put the entire directory from disk.");
        System.out.println("GETCHKDIR:<path>[#<defaultfile>] - Get the key that would be returned if we'd put the entire directory from disk.");
        System.out.println("MAKESSK - Create an SSK keypair.");
        System.out.println("PUTSSK:<insert uri>;<url to redirect to> - Insert an SSK redirect to a file already inserted.");
        System.out.println("PUTSSKDIR:<insert uri>#<path>[#<defaultfile>] - Insert an entire directory to an SSK.");
        System.out.println("PLUGLOAD: - Load plugin. (use \"PLUGLOAD:?\" for more info)");
        //System.out.println("PLUGLOAD: <pkg.classname>[(@<URI to jarfile.jar>|<<URI to file containing real URI>|* (will load from freenets pluginpool))] - Load plugin.");
        System.out.println("PLUGLIST - List all loaded plugins.");
        System.out.println("PLUGKILL: <pluginID> - Unload the plugin with the given ID (see PLUGLIST).");
//        System.out.println("PUBLISH:<name> - create a publish/subscribe stream called <name>");
//        System.out.println("PUSH:<name>:<text> - publish a single line of text to the stream named");
//        System.out.println("SUBSCRIBE:<key> - subscribe to a publish/subscribe stream by key");
        System.out.println("CONNECT:<filename|URL> - connect to a node from its ref in a file/url.");
        System.out.println("CONNECT:\n<noderef including an End on a line by itself> - enter a noderef directly.");
        System.out.println("DISCONNECT:<ip:port> - disconnect from a node by providing it's ip+port or name");
        System.out.println("NAME:<new node name> - change the node's name.");
//        System.out.println("SUBFILE:<filename> - append all data received from subscriptions to a file, rather than sending it to stdout.");
//        System.out.println("SAY:<text> - send text to the last created/pushed stream");
        System.out.println("STATUS - display some status information on the node including its reference and connections.");
        System.out.println("QUIT - exit the program");
        if(n.testnetEnabled) {
        	System.out.println("WARNING: TESTNET MODE ENABLED. YOU HAVE NO ANONYMITY.");
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
				Bucket data = result.asBucket();
				// FIXME limit it above
				if(data.size() > 32*1024) {
					System.err.println("Data is more than 32K: "+data.size());
					return;
				}
				byte[] dataBytes = BucketTools.toByteArray(data);
				boolean evil = false;
				for(int i=0;i<dataBytes.length;i++) {
					// Look for escape codes
					if(dataBytes[i] == '\n') continue;
					if(dataBytes[i] == '\r') continue;
					if(dataBytes[i] < 32) evil = true;
				}
				if(evil) {
					System.err.println("Data may contain escape codes which could cause the terminal to run arbitrary commands! Save it to a file if you must with GETFILE:");
					return;
				}
				System.out.println("Data:\n");
				System.out.println(new String(dataBytes));
				System.out.println();
			} catch (FetchException e) {
				System.out.println("Error: "+e.getMessage());
            	if(e.getMode() == e.SPLITFILE_ERROR && e.errorCodes != null) {
            		System.out.println(e.errorCodes.toVerboseString());
            	}
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
                    fnam = "freenet-download-"+HexUtil.bytesToHex(BucketTools.hash(data), 0, 10);
                    String ext = DefaultMIMETypes.getExtension(cm.getMIMEType());
                    if(ext != null && !ext.equals(""))
                    	fnam += "." + ext;
                }
                File f = new File(downloadsDir, fnam);
                if(f.exists()) {
                    System.out.println("File exists already: "+fnam);
                    fnam = "freenet-"+System.currentTimeMillis()+"-"+fnam;
                }
                FileOutputStream fos = null;
                try {
                    fos = new FileOutputStream(f);
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
            	if(e.getMode() == e.SPLITFILE_ERROR && e.errorCodes != null) {
            		System.out.println(e.errorCodes.toVerboseString());
            	}
			}
        } else if(uline.startsWith("QUIT")) {
            System.out.println("Goodbye.");
            System.exit(0);
        } else if(uline.startsWith("PUT:") || (getCHKOnly = uline.startsWith("GETCHK:"))) {
            // Just insert to local store
        	if(getCHKOnly)
        		line = line.substring(("GETCHK:").length());
        	else
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
            	int mode = e.getMode();
            	if(mode == InserterException.FATAL_ERRORS_IN_BLOCKS || mode == InserterException.TOO_MANY_RETRIES_IN_BLOCKS) {
            		System.out.println("Splitfile-specific error:\n"+e.errorCodes.toVerboseString());
            	}
            	return;
            }
            
            System.out.println("URI: "+uri);
            ////////////////////////////////////////////////////////////////////////////////
        } else if(uline.startsWith("PUTDIR:") || (uline.startsWith("PUTSSKDIR")) || (getCHKOnly = uline.startsWith("GETCHKDIR:"))) {
        	// TODO: Check for errors?
        	boolean ssk = false;
        	if(uline.startsWith("PUTDIR:"))
        		line = line.substring("PUTDIR:".length());
        	else if(uline.startsWith("PUTSSKDIR:")) {
        		line = line.substring("PUTSSKDIR:".length());
        		ssk = true;
        	} else if(uline.startsWith("GETCHKDIR:"))
        		line = line.substring(("GETCHKDIR:").length());
        	else
        		System.err.println("Impossible");
        	
        	line = line.trim();
        	
        	if(line.length() < 1) {
        		printHeader();
        		return;
        	}
        	
        	String defaultFile = null;
        	
        	FreenetURI insertURI = FreenetURI.EMPTY_CHK_URI;
        	
        	// set default file?
        	if (line.matches("^.*#.*$")) {
        		String[] split = line.split("#");
        		if(ssk) {
        			insertURI = new FreenetURI(split[0]);
        			line = split[1];
        			if(split.length > 2)
        				defaultFile = split[2];
        		} else {
        			defaultFile = split[1];
        			line = split[0];
        		}
        	}
        	
        	HashMap bucketsByName =
        		makeBucketsByName(line);
        	
        	if(defaultFile == null) {
        		String[] defaultFiles = 
        			new String[] { "index.html", "index.htm", "default.html", "default.htm" };
        		for(int i=0;i<defaultFiles.length;i++) {
        			if(bucketsByName.containsKey(defaultFiles[i])) {
        				defaultFile = defaultFiles[i];
        				break;
        			}        				
        		}
        	}
        	
        	FreenetURI uri;
			try {
				uri = client.insertManifest(insertURI, bucketsByName, defaultFile);
				uri = uri.addMetaStrings(new String[] { "" });
	        	System.out.println("=======================================================");
	            System.out.println("URI: "+uri);
	        	System.out.println("=======================================================");
			} catch (InserterException e) {
            	System.out.println("Finished insert but: "+e.getMessage());
            	if(e.uri != null) {
            		uri = e.uri;
    				uri = uri.addMetaStrings(new String[] { "" });
            		System.out.println("URI would have been: "+uri);
            	}
            	if(e.errorCodes != null) {
            		System.out.println("Splitfile errors breakdown:");
            		System.out.println(e.errorCodes.toVerboseString());
            	}
            	Logger.error(this, "Caught "+e, e);
			}
            
        } else if(uline.startsWith("PUTFILE:") || (getCHKOnly = uline.startsWith("GETCHKFILE:"))) {
            // Just insert to local store
        	if(getCHKOnly) {
        		line = line.substring(("GETCHKFILE:").length());
        	} else {
        		line = line.substring("PUTFILE:".length());
        	}
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
            	if(mimeType.equals(DefaultMIMETypes.DEFAULT_MIME_TYPE))
            		mimeType = ""; // don't need to override it
            	
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
            	if(e.errorCodes != null) {
            		System.out.println("Splitfile errors breakdown:");
            		System.out.println(e.errorCodes.toVerboseString());
            	}
            } catch (Throwable t) {
                System.out.println("Insert threw: "+t);
                t.printStackTrace();
            }
        } else if(uline.startsWith("MAKESSK")) {
        	InsertableClientSSK key = InsertableClientSSK.createRandom(r);
        	System.out.println("Insert URI: "+key.getInsertURI().toString(false));
        	System.out.println("Request URI: "+key.getURI().toString(false));
        	FreenetURI insertURI = key.getInsertURI().setDocName("testsite");
        	String fixedInsertURI = insertURI.toString(false);
        	System.out.println("Note that you MUST add a filename to the end of the above URLs e.g.:\n"+fixedInsertURI);
        	System.out.println("Normally you will then do PUTSSKDIR:<insert URI>#<directory to upload>, for example:\nPUTSSKDIR:"+fixedInsertURI+"#directoryToUpload/");
        	System.out.println("This will then produce a manifest site containing all the files, the default document can be accessed at\n"+insertURI.addMetaStrings(new String[] { "" }).toString(false));
        } else if(uline.startsWith("PUTSSK:")) {
        	String cmd = line.substring("PUTSSK:".length());
        	cmd = cmd.trim();
        	if(cmd.indexOf(';') <= 0) {
        		System.out.println("No target URI provided.");
        		System.out.println("PUTSSK:<insert uri>;<url to redirect to>");
        		return;
        	}
        	String[] split = cmd.split(";");
        	String insertURI = split[0];
        	String targetURI = split[1];
        	System.out.println("Insert URI: "+insertURI);
        	System.out.println("Target URI: "+targetURI);
        	FreenetURI insert = new FreenetURI(insertURI);
        	FreenetURI target = new FreenetURI(targetURI);
        	InsertableClientSSK key = InsertableClientSSK.create(insert);
        	System.out.println("Fetch URI: "+key.getURI());
        	try {
				FreenetURI result = client.insertRedirect(insert, target);
				System.out.println("Successfully inserted to fetch URI: "+key.getURI());
			} catch (InserterException e) {
            	System.out.println("Finished insert but: "+e.getMessage());
            	Logger.normal(this, "Error: "+e, e);
            	if(e.uri != null) {
            		System.out.println("URI would have been: "+e.uri);
            	}
			}
        	
        } else if(uline.startsWith("STATUS")) {
            SimpleFieldSet fs = n.exportFieldSet();
            System.out.println(fs.toString());
            System.out.println();
            System.out.println(n.getStatus());
	    if(Version.buildNumber()<Version.highestSeenBuild){
	            System.out.println("The latest version is : "+Version.highestSeenBuild);
	    }
        } else if(uline.startsWith("CONNECT:")) {
            String key = line.substring("CONNECT:".length());
            while(key.length() > 0 && key.charAt(0) == ' ')
                key = key.substring(1);
            while(key.length() > 0 && key.charAt(key.length()-1) == ' ')
                key = key.substring(0, key.length()-2);
            
            String content = null;
            if(key.length() > 0) {
                // Filename
            	BufferedReader in;
                System.out.println("Trying to connect to noderef in "+key);
                File f = new File(key);
                if (f.isFile()) {
                	System.out.println("Given string seems to be a file, loading...");
                	in = new BufferedReader(new FileReader(f));
                } else {
                	System.out.println("Given string seems to be an URL, loading...");
                    URL url = new URL(key);
                    URLConnection uc = url.openConnection();
                	in = new BufferedReader(
                			new InputStreamReader(uc.getInputStream()));
                }
                content = readLines(in, true);
                in.close();
            } else {
                content = readLines(reader, true);
            }
            if(content == null) return;
            if(content.equals("")) return;
            connect(content);
        
        } else if(uline.startsWith("NAME:")) {
            System.out.println("Node name currently: "+n.myName);
            String key = line.substring("NAME:".length());
            while(key.length() > 0 && key.charAt(0) == ' ')
                key = key.substring(1);
            while(key.length() > 0 && key.charAt(key.length()-1) == ' ')
                key = key.substring(0, key.length()-2);
            System.out.println("New name: "+key);
            n.setName(key);
        } else if(uline.startsWith("DISCONNECT:")) {
        	String ipAndPort = line.substring("DISCONNECT:".length());
        	disconnect(ipAndPort.trim());
        	
        } else if(uline.startsWith("PLUGLOAD:")) {
        	if (line.substring("PLUGLOAD:".length()).trim().equals("?")) {
        		System.out.println("  PLUGLOAD: pkg.Class                        - Load plugin from current classpath");        		
        		System.out.println("  PLUGLOAD: pkg.Class@file:<filename>        - Load plugin from file");
        		System.out.println("  PLUGLOAD: pkg.Class@http://...             - Load plugin from online file");
        		System.out.println("");
        		System.out.println("If the filename/url ends with \".url\", it" +
        				" is treated as a link, meaning that the first line is" +
        				" the accual URL. Else it is loaded as classpath and" +
        				" the class it loaded from it (meaning the file could" +
        				" be either a jar-file or a class-file).");
        		System.out.println("");
        		System.out.println("  PLUGLOAD: pkg.Class*  - Load newest version of plugin from http://downloads.freenetproject.org/alpha/plugins/");        		
        		System.out.println("");
        		
        	} else
        		n.pluginManager.startPlugin(line.substring("PLUGLOAD:".length()).trim());
            //System.out.println("PLUGLOAD: <pkg.classname>[(@<URI to jarfile.jar>|<<URI to file containing real URI>|* (will load from freenets pluginpool))] - Load plugin.");
        } else if(uline.startsWith("PLUGLIST")) {
        	System.out.println(n.pluginManager.dumpPlugins());
        } else if(uline.startsWith("PLUGKILL:")) {
        	n.pluginManager.killPlugin(line.substring("PLUGKILL:".length()).trim());
        } else {
        	if(uline.length() > 0)
        		printHeader();
        }
    }

    /**
     * Create a map of String -> Bucket for every file in a directory
     * and its subdirs.
     */
    private HashMap makeBucketsByName(String directory) {
    	
    	if (!directory.endsWith("/"))
    		directory = directory + "/";
    	File thisdir = new File(directory);
    	
    	System.out.println("Listing dir: "+thisdir);
    	
    	HashMap ret = new HashMap();
    	
    	File filelist[] = thisdir.listFiles();
    	if(filelist == null)
    		throw new IllegalArgumentException("No such directory");
    	for(int i = 0 ; i < filelist.length ; i++) {
    		if (filelist[i].isFile() && filelist[i].canRead()) {
    			File f = filelist[i];
    			
    			FileBucket bucket = new FileBucket(f, true, false, false);
    			
    			ret.put(f.getName(), bucket);
    		} else if(filelist[i].isDirectory()) {
    			HashMap subdir = makeBucketsByName(directory + filelist[i].getName());
    			Iterator it = subdir.keySet().iterator();
    			while(it.hasNext()) {
    				String key = (String) it.next();
    				Bucket bucket = (Bucket) subdir.get(key);
    				ret.put(filelist[i].getName() + "/" + key, bucket);
    			}
    		}
    	}
    	return ret;
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
            fs = new SimpleFieldSet(content, false);
        } catch (IOException e) {
            System.err.println("Did not parse: "+e);
            e.printStackTrace();
            return;
        }
        PeerNode pn;
        try {
            pn = new PeerNode(fs, n);
        } catch (FSParseException e1) {
            System.err.println("Did not parse: "+e1);
            Logger.error(this, "Did not parse: "+e1, e1);
            return;
        } catch (PeerParseException e1) {
            System.err.println("Did not parse: "+e1);
            Logger.error(this, "Did not parse: "+e1, e1);
            return;
        }
        if(n.peers.addPeer(pn))
            System.out.println("Added peer: "+pn);
        n.peers.writePeers();
    }
    
    /**
     * Disconnect from a node, given its ip and port, or name, as a String
     */
    private void disconnect(String nodeIdentifier) {
    	System.out.println("Disconnecting from node at: "+nodeIdentifier);
    	PeerNode[] pn = n.peers.myPeers;
    	for(int i=0;i<pn.length;i++)
    	{
    		Peer peer = pn[i].getDetectedPeer();
    		String nodeIpAndPort = "";
    		if(peer != null) {
        		nodeIpAndPort = peer.getAddress().getHostAddress()+":"+pn[i].getDetectedPeer().getPort();
    		}
    		String name = pn[i].myName;
    		if(nodeIpAndPort.equals(nodeIdentifier) || name.equals(nodeIdentifier))
    		{
    			n.peers.disconnect(pn[i]);
    			return;
    		}
    	}
    	System.out.println("No node in peers list at: "+nodeIdentifier);
    }

    private String sanitize(String fnam) {
    	if(fnam == null) return "";
        StringBuffer sb = new StringBuffer(fnam.length());
        for(int i=0;i<fnam.length();i++) {
            char c = fnam.charAt(i);
            if(Character.isLetterOrDigit(c) || c == '-' || c == '.')
                sb.append(c);
        }
        return sb.toString();
    }

	public static void maybeCreate(Node node, Config config) {
		// FIXME make this configurable.
		// Depends on fixing QUIT issues. (bug #81)
		new TextModeClientInterface(node);
	}
}
