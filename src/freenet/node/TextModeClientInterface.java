package freenet.node;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;

import freenet.client.ClientMetadata;
import freenet.client.DefaultMIMETypes;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.client.HighLevelSimpleClientImpl;
import freenet.client.InsertBlock;
import freenet.client.InserterException;
import freenet.client.Metadata;
import freenet.client.events.EventDumper;
import freenet.crypt.RandomSource;
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
    final RequestStarterClient requestStarterClient;
    final RequestStarterClient insertStarterClient;
    final File downloadsDir;
    
    TextModeClientInterface(Node n) {
        this.n = n;
        client = n.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS, (short)0);
        client.addGlobalHook(new EventDumper());
        this.r = n.random;
        streams = new Hashtable();
        new Thread(this, "Text mode client interface").start();
        this.requestStarterClient = n.makeStarterClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS, (short)0, false);
        this.insertStarterClient = n.makeStarterClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS, (short)0, true);
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
        System.out.println("PUTDIR:<path>[#<defaultfile>] - Put the entire directory from disk.");
        System.out.println("GETCHKDIR:<path>[#<defaultfile>] - Get the key that would be returned if we'd put the entire directory from disk.");
        System.out.println("MAKESSK - Create an SSK keypair.");
        System.out.println("PUTSSK:<insert uri>;<url to redirect to> - Insert an SSK redirect to a file already inserted.");
//        System.out.println("PUBLISH:<name> - create a publish/subscribe stream called <name>");
//        System.out.println("PUSH:<name>:<text> - publish a single line of text to the stream named");
//        System.out.println("SUBSCRIBE:<key> - subscribe to a publish/subscribe stream by key");
        System.out.println("CONNECT:<filename> - connect to a node from its ref in a file.");
        System.out.println("CONNECT:\n<noderef including an End on a line by itself> - enter a noderef directly.");
        System.out.println("DISCONNECT:<ip:port> - disconnect from a node by providing it's ip+port");
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
        } else if(uline.startsWith("PUTDIR:") || (getCHKOnly = uline.startsWith("GETCHKDIR:"))) {
        	// TODO: Check for errors?
        	if(getCHKOnly) {
        		line = line.substring(("GETCHKDIR:").length());
        	} else {
        		line = line.substring("PUTDIR:".length());
        	}
        	
        	line = line.trim();
        	
        	if(line.length() < 1) {
        		printHeader();
        		return;
        	}
        	
        	String defaultFile = null;
        	
        	// set default file?
        	if (line.matches("^.*#.*$")) {
        		defaultFile = line.split("#")[1];
        		line = line.split("#")[0];
        	}
        	
        	// Get files as name and keys
        	HashMap manifestBase = dirPut(line, getCHKOnly);
        	
        	// Set defaultfile
        	if (defaultFile != null) {
        		HashMap currPos = manifestBase;
        		String splitpath[] = defaultFile.split("/");
        		int i = 0;
        		for( ; i < (splitpath.length - 1) ; i++)
        			currPos = (HashMap)currPos.get(splitpath[i]);
        		
        		if (currPos.get(splitpath[i]) != null) {
        			// Add key as default
        			manifestBase.put("", currPos.get(splitpath[i]));
        			System.out.println("Using default key: " + currPos.get(splitpath[i]));
        		}else{
        			System.err.println("Default key not found. No default document.");
        		}
        		//getchkdir:/home/cyberdo/fort/new#filelist
        	}
        	
        	// Create metadata
            Metadata med = Metadata.mkRedirectionManifest(manifestBase);
            ClientMetadata md = med.getClientMetadata();
            
            // Extract binary data from metadata
            ArrayBucket metabucket = new ArrayBucket();
            DataOutputStream mdos = new DataOutputStream( metabucket.getOutputStream() );
            med.writeTo(mdos);
            mdos.close();
            
            // Insert metadata
            InsertBlock block = new InsertBlock(metabucket, md, FreenetURI.EMPTY_CHK_URI);
            
            FreenetURI uri;
            try {
            	uri = ((HighLevelSimpleClientImpl)client).insert(block, getCHKOnly, true);
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
            
        	String filelist = dirPutToList(manifestBase, "");
        	System.out.println("=======================================================");
        	System.out.println(filelist);
        	System.out.println("=======================================================");
            System.out.println("URI: "+uri);
        	System.out.println("=======================================================");
            
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
            	if(e.uri != null) {
            		System.out.println("URI would have been: "+e.uri);
            	}
			}
        	
        } else if(uline.startsWith("STATUS")) {
            SimpleFieldSet fs = n.exportFieldSet();
            System.out.println(fs.toString());
            System.out.println();
            System.out.println(n.getStatus());
	    if(Version.buildNumber<Version.highestSeenBuild){
	            System.out.println("The latest version is : "+Version.highestSeenBuild);
	    }
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
        } else if(uline.startsWith("DISCONNECT:")) {
        	String ipAndPort = line.substring("DISCONNECT:".length());
        	disconnect(ipAndPort.trim());
        } else {
        	if(uline.length() > 0)
        		printHeader();
        }
    }

    
    private String dirPutToList(HashMap dir, String basedir) {
    	String ret = "";
		for(Iterator i = dir.keySet().iterator();i.hasNext();) {
			String key = (String) i.next();
			Object o = dir.get(key);
			Metadata target;
			if(o instanceof String) {
				// File
				ret += basedir + key + "\n";
			} else if(o instanceof HashMap) {
				ret += dirPutToList((HashMap)o, basedir + key + "//");
			} else throw new IllegalArgumentException("Not String nor HashMap: "+o);
		}
		return ret;
    }
    
    private HashMap dirPut(String directory, boolean getCHKOnly) {
    	if (!directory.endsWith("/"))
    		directory = directory + "/";
    	File thisdir = new File(directory);
    	
    	System.out.println("Listing dir: "+thisdir);
    	
    	HashMap ret = new HashMap();
    	
    	File filelist[] = thisdir.listFiles();
    	for(int i = 0 ; i < filelist.length ; i++)
    		if (filelist[i].isFile()) {
    			FreenetURI uri = null;
    			File f = filelist[i];
    			String line = f.getAbsolutePath(); 
    			// To ease cleanup, the following code is taken from above
    			// Except for the uri-declaration above.
    			// Somelines is also commented out
    			//////////////////////////////////////////////////////////////////////////////////////
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
                	// Declaration is moved out!!!!!!!!!!!!
                	uri = client.insert(block, getCHKOnly);
                	
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
                //////////////////////////////////////////////////////////////////////////////////////
    			
                if (uri != null)
                	ret.put(filelist[i].getName(), uri.toString(false));
                else
                	System.err.println("Could not insert file.");
                //ret.put(filelist[i].getName(), null);
    		} else {
    			HashMap subdir = dirPut(filelist[i].getAbsolutePath(), getCHKOnly);
    			ret.put(filelist[i].getName(), subdir);
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
    
    /**
     * Disconnect from a node, given its ip and port as a String
     */
    private void disconnect(String ipAndPort) {
    	System.out.println("Disconnecting from node at: "+ipAndPort);
    	PeerNode[] pn = n.peers.myPeers;
    	for(int i=0;i<pn.length;i++)
    	{
    		String nodeIpAndPort = pn[i].getPeer().getAddress().getHostAddress()+":"+pn[i].getPeer().getPort();
    		if(nodeIpAndPort.equals(ipAndPort))
    		{
    			n.peers.disconnect(pn[i]);
    			return;
    		}
    	}
    	System.out.println("No node in peers list at: "+ipAndPort);
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
}
