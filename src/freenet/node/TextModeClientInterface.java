package freenet.node;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
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
import freenet.io.comm.Peer;
import freenet.io.comm.PeerParseException;
import freenet.keys.FreenetURI;
import freenet.keys.InsertableClientSSK;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.io.ArrayBucket;
import freenet.support.io.Bucket;
import freenet.support.io.BucketTools;
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
    final InputStream in;
    final OutputStream out;
    
    public TextModeClientInterface(TextModeClientInterfaceServer server, InputStream in, OutputStream out) {
    	this.n = server.n;
    	this.r = server.r;
        client = server.n.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS);
    	this.streams = new Hashtable();
    	this.downloadsDir = server.downloadsDir;
    	this.in = in;
    	this.out = out;
        client.addGlobalHook(new EventDumper(new PrintWriter(out, true)));
	}

    public TextModeClientInterface(Node n, HighLevelSimpleClient c, File downloadDir, InputStream in, OutputStream out) {
    	this.n = n;
    	this.r = n.random;
    	this.client = c;
    	this.streams = new Hashtable();
    	this.downloadsDir = downloadDir;
    	this.in = in;
    	this.out = out;
        client.addGlobalHook(new EventDumper(new PrintWriter(out, true)));
    }
    
    public void run() {
    	try {
    		realRun();
    	} catch (IOException e) {
    		Logger.minor(this, "Caught "+e, e);
    	} catch (Throwable t) {
    		Logger.error(this, "Caught "+t, t);
    	}
    }
	
	public void realRun() throws IOException {
		printHeader(out);

		BufferedReader reader = new BufferedReader(new InputStreamReader(in));
		while(true) {
			try {
				out.write("TMCI> ".getBytes());
				out.flush();
				if(processLine(reader,out)) {
					reader.close();
					return;
				}
			} catch (SocketException e) {
				Logger.error(this, "Socket error: "+e, e);
				return;
			} catch (Throwable t) {
				Logger.error(this, "Caught "+t, t);
				System.out.println("Caught: "+t);
				StringWriter sw = new StringWriter();
				t.printStackTrace(new PrintWriter(sw));
				try {
					out.write(sw.toString().getBytes());
				} catch (IOException e) {
					Logger.error(this, "Socket error: "+e, e);
					return;
				}
			}
		}
	}
    
	private void printHeader(OutputStream s) throws IOException {
    	StringBuffer sb = new StringBuffer();
    	
        sb.append("Trivial Text Mode Client Interface\r\n");
        sb.append("---------------------------------------\r\n");
        sb.append("Freenet 0.7 Build #"+Version.buildNumber()+" r"+Version.cvsRevision+"\r\n");
        sb.append("Enter one of the following commands:\r\n");
        sb.append("GET:<Freenet key> - Fetch a key\r\n");
        sb.append("PUT:\\r\\n<text, until a . on a line by itself> - Insert the document and return the key.\r\n");
        sb.append("PUT:<text> - Put a single line of text to a CHK and return the key.\r\n");
        sb.append("GETCHK:\\r\\n<text, until a . on a line by itself> - Get the key that would be returned if the document was inserted.\r\n");
        sb.append("GETCHK:<text> - Get the key that would be returned if the line was inserted.\r\n");
        sb.append("PUTFILE:<filename>[#<mimetype>] - Put a file from disk.\r\n");
        sb.append("GETFILE:<filename> - Fetch a key and put it in a file. If the key includes a filename we will use it but we will not overwrite local files.\r\n");
        sb.append("GETCHKFILE:<filename> - Get the key that would be returned if we inserted the file.\r\n");
        sb.append("PUTDIR:<path>[#<defaultfile>] - Put the entire directory from disk.\r\n");
        sb.append("GETCHKDIR:<path>[#<defaultfile>] - Get the key that would be returned if we'd put the entire directory from disk.\r\n");
        sb.append("MAKESSK - Create an SSK keypair.\r\n");
        sb.append("PUTSSK:<insert uri>;<url to redirect to> - Insert an SSK redirect to a file already inserted.\r\n");
        sb.append("PUTSSKDIR:<insert uri>#<path>[#<defaultfile>] - Insert an entire directory to an SSK.\r\n");
        sb.append("PLUGLOAD: - Load plugin. (use \"PLUGLOAD:?\" for more info)\r\n");
        //sb.append("PLUGLOAD: <pkg.classname>[(@<URI to jarfile.jar>|<<URI to file containing real URI>|* (will load from freenets pluginpool))] - Load plugin.\r\n");
        sb.append("PLUGLIST - List all loaded plugins.\r\n");
        sb.append("PLUGKILL:<pluginID> - Unload the plugin with the given ID (see PLUGLIST).\r\n");
//        sb.append("PUBLISH:<name> - create a publish/subscribe stream called <name>\r\n");
//        sb.append("PUSH:<name>:<text> - publish a single line of text to the stream named\r\n");
//        sb.append("SUBSCRIBE:<key> - subscribe to a publish/subscribe stream by key\r\n");
        sb.append("CONNECT:<filename|URL> - see ADDPEER:<filename|URL> below\r\n");
        sb.append("CONNECT:\\r\\n<noderef> - see ADDPEER:\\r\\n<noderef> below\r\n");
        sb.append("DISCONNECT:<ip:port|name> - see REMOVEPEER:<ip:port|name|identity> below\r\n");
        sb.append("ADDPEER:<filename|URL> - add a peer from its ref in a file/url.\r\n");
        sb.append("ADDPEER:\\r\\n<noderef including an End on a line by itself> - add a peer by entering a noderef directly.\r\n");
        sb.append("DISABLEPEER:<ip:port|name|identity> - disable a peer by providing its ip+port, name, or identity\r\n");
        sb.append("ENABLEPEER:<ip:port|name|identity> - enable a peer by providing its ip+port, name, or identity\r\n");
        sb.append("SETPEERLISTENONLY:<ip:port|name|identity> - set ListenOnly on a peer by providing its ip+port, name, or identity\r\n");
        sb.append("UNSETPEERLISTENONLY:<ip:port|name|identity> - unset ListenOnly on a peer by providing its ip+port, name, or identity\r\n");
        sb.append("HAVEPEER:<ip:port|name|identity> - report true/false on having a peer by providing its ip+port, name, or identity\r\n");
        sb.append("REMOVEPEER:<ip:port|name|identity> - remove a peer by providing its ip+port, name, or identity\r\n");
        sb.append("PEER:<ip:port|name|identity> - report the noderef of a peer (without metadata) by providing its ip+port, name, or identity\r\n");
        sb.append("PEERWMD:<ip:port|name|identity> - report the noderef of a peer (with metadata) by providing its ip+port, name, or identity\r\n");
        sb.append("PEERS - report tab delimited list of peers with name, ip+port, identity, location, status and idle time in seconds\r\n");
        sb.append("NAME:<new node name> - change the node's name.\r\n");
        sb.append("UPDATE ask the node to self-update if possible. \r\n");
//        sb.append("SUBFILE:<filename> - append all data received from subscriptions to a file, rather than sending it to stdout.\r\n");
//        sb.append("SAY:<text> - send text to the last created/pushed stream\r\n");
        sb.append("STATUS - display some status information on the node including its reference and connections.\r\n");
        sb.append("SHUTDOWN - exit the program\r\n");
        if(n.isUsingWrapper())
        	sb.append("RESTART - restart the program\r\n");
        if(n.directTMCI != this) {
          sb.append("QUIT - close the socket\r\n");
        }
        if(n.testnetEnabled) {
        	sb.append("WARNING: TESTNET MODE ENABLED. YOU HAVE NO ANONYMITY.\r\n");
        }
        s.write(sb.toString().getBytes());
    }

	/**
     * Process a single command.
     * @throws IOException If we could not write the data to stdout.
     */
    private boolean processLine(BufferedReader reader, OutputStream out) throws IOException {
        String line;
        StringBuffer outsb = new StringBuffer();
        try {
            line = reader.readLine();
        } catch (IOException e) {
            outsb.append("Bye... ("+e+")");
            System.err.println("Bye... ("+e+")");
            return true;
        }
        boolean getCHKOnly = false;
        if(line == null) return true;
        String uline = line.toUpperCase();
        Logger.minor(this, "Command: "+line);
        if(uline.startsWith("GET:")) {
            // Should have a key next
            String key = line.substring("GET:".length());
            while((key.length() > 0) && (key.charAt(0) == ' '))
                key = key.substring(1);
            while((key.length() > 0) && (key.charAt(key.length()-1) == ' '))
                key = key.substring(0, key.length()-2);
            Logger.normal(this, "Key: "+key);
            FreenetURI uri;
            try {
                uri = new FreenetURI(key);
                Logger.normal(this, "Key: "+uri);
            } catch (MalformedURLException e2) {
                outsb.append("Malformed URI: "+key+" : "+e2);
                return false;
            }
            try {
				FetchResult result = client.fetch(uri);
				ClientMetadata cm = result.getMetadata();
				outsb.append("Content MIME type: "+cm.getMIMEType());
				Bucket data = result.asBucket();
				// FIXME limit it above
				if(data.size() > 32*1024) {
					System.err.println("Data is more than 32K: "+data.size());
					outsb.append("Data is more than 32K: "+data.size());
					return false;
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
					outsb.append("Data may contain escape codes which could cause the terminal to run arbitrary commands! Save it to a file if you must with GETFILE:");
						return false;
				}
				outsb.append("Data:\r\n");
				outsb.append(new String(dataBytes));
			} catch (FetchException e) {
				outsb.append("Error: "+e.getMessage()+"\r\n");
            	if((e.getMode() == FetchException.SPLITFILE_ERROR) && (e.errorCodes != null)) {
            		outsb.append(e.errorCodes.toVerboseString());
            	}
            	if(e.newURI != null)
            		outsb.append("Permanent redirect: "+e.newURI+"\r\n");
			}
        } else if(uline.startsWith("GETFILE:")) {
            // Should have a key next
            String key = line.substring("GETFILE:".length());
            while((key.length() > 0) && (key.charAt(0) == ' '))
                key = key.substring(1);
            while((key.length() > 0) && (key.charAt(key.length()-1) == ' '))
                key = key.substring(0, key.length()-2);
            Logger.normal(this, "Key: "+key);
            FreenetURI uri;
            try {
                uri = new FreenetURI(key);
            } catch (MalformedURLException e2) {
                outsb.append("Malformed URI: "+key+" : "+e2);
                return false;
            }
            try {
            	long startTime = System.currentTimeMillis();
				FetchResult result = client.fetch(uri);
				ClientMetadata cm = result.getMetadata();
				outsb.append("Content MIME type: "+cm.getMIMEType());
				Bucket data = result.asBucket();
                // Now calculate filename
                String fnam = uri.getDocName();
                fnam = sanitize(fnam);
                if(fnam.length() == 0) {
                    fnam = "freenet-download-"+HexUtil.bytesToHex(BucketTools.hash(data), 0, 10);
                    String ext = DefaultMIMETypes.getExtension(cm.getMIMEType());
                    if((ext != null) && !ext.equals(""))
                    	fnam += "." + ext;
                }
                File f = new File(downloadsDir, fnam);
                if(f.exists()) {
                    outsb.append("File exists already: "+fnam);
                    fnam = "freenet-"+System.currentTimeMillis()+"-"+fnam;
                }
                FileOutputStream fos = null;
                try {
                    fos = new FileOutputStream(f);
                    BucketTools.copyTo(data, fos, Long.MAX_VALUE);
                    fos.close();
                    outsb.append("Written to "+fnam);
                } catch (IOException e) {
                    outsb.append("Could not write file: caught "+e);
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
                outsb.append("Download rate: "+rate+" bytes / second");
			} catch (FetchException e) {
				outsb.append("Error: "+e.getMessage());
            	if((e.getMode() == FetchException.SPLITFILE_ERROR) && (e.errorCodes != null)) {
            		outsb.append(e.errorCodes.toVerboseString());
            	}
            	if(e.newURI != null)
            		outsb.append("Permanent redirect: "+e.newURI+"\r\n");
			}
    } else if(uline.startsWith("UPDATE")) {
    		outsb.append("starting the update process");
    		// FIXME run on separate thread
    		n.ps.queueTimedJob(new Runnable() {
    			public void run() {
    				n.getNodeUpdater().Update();
    			}
    		}, 0);
    		return false;
    }else if(uline.startsWith("BLOW")) {
    			n.getNodeUpdater().blow("caught an  IOException : (Incompetent Operator) :p");
    			return false;
	} else if(uline.startsWith("SHUTDOWN")) {
		StringBuffer sb = new StringBuffer();
		sb.append("Shutting node down.\r\n");
		out.write(sb.toString().getBytes());
		out.flush();
		n.exit();
	} else if(uline.startsWith("RESTART")) {
		StringBuffer sb = new StringBuffer();
		sb.append("Restarting the node.\r\n");
		out.write(sb.toString().getBytes());
		out.flush();
		n.getNodeStarter().restart();
	} else if(uline.startsWith("QUIT") && (n.directTMCI == this)) {
		StringBuffer sb = new StringBuffer();
		sb.append("QUIT command not available in console mode.\r\n");
		out.write(sb.toString().getBytes());
		out.flush();
		return false;
        } else if(uline.startsWith("QUIT")) {
		StringBuffer sb = new StringBuffer();
		sb.append("Closing connection.\r\n");
		out.write(sb.toString().getBytes());
		out.flush();
		return true;
	} else if(uline.startsWith("HELP")) {
		printHeader(out);
		return false;
        } else if(uline.startsWith("PUT:") || (getCHKOnly = uline.startsWith("GETCHK:"))) {
            // Just insert to local store
        	if(getCHKOnly)
        		line = line.substring(("GETCHK:").length());
        	else
        		line = line.substring("PUT:".length());
            while((line.length() > 0) && (line.charAt(0) == ' '))
                line = line.substring(1);
            while((line.length() > 0) && (line.charAt(line.length()-1) == ' '))
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
            	outsb.append("Error: "+e.getMessage());
            	if(e.uri != null)
            		outsb.append("URI would have been: "+e.uri);
            	int mode = e.getMode();
            	if((mode == InserterException.FATAL_ERRORS_IN_BLOCKS) || (mode == InserterException.TOO_MANY_RETRIES_IN_BLOCKS)) {
            		outsb.append("Splitfile-specific error:\n"+e.errorCodes.toVerboseString());
            	}
            	return false;
            }
            
            outsb.append("URI: "+uri);
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
        	else {
        		System.err.println("Impossible");
        		outsb.append("Impossible");
        	}
        	
        	line = line.trim();
        	
        	if(line.length() < 1) {
        		printHeader(out);
        		return false;
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
	        	outsb.append("=======================================================");
	            outsb.append("URI: "+uri);
	        	outsb.append("=======================================================");
			} catch (InserterException e) {
            	outsb.append("Finished insert but: "+e.getMessage());
            	if(e.uri != null) {
            		uri = e.uri;
    				uri = uri.addMetaStrings(new String[] { "" });
            		outsb.append("URI would have been: "+uri);
            	}
            	if(e.errorCodes != null) {
            		outsb.append("Splitfile errors breakdown:");
            		outsb.append(e.errorCodes.toVerboseString());
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
            while((line.length() > 0) && (line.charAt(0) == ' '))
                line = line.substring(1);
            while((line.length() > 0) && (line.charAt(line.length()-1) == ' '))
                line = line.substring(0, line.length()-2);
            String mimeType = DefaultMIMETypes.guessMIMEType(line);
            if (line.indexOf('#') > -1) {
            	String[] splittedLine = line.split("#");
            	line = splittedLine[0];
            	mimeType = splittedLine[1];
            }
            File f = new File(line);
            outsb.append("Attempting to read file "+line);
            long startTime = System.currentTimeMillis();
            try {
            	if(!(f.exists() && f.canRead())) {
            		throw new FileNotFoundException();
            	}
            	
            	// Guess MIME type
            	outsb.append("Using MIME type: "+mimeType + "\r\n");
            	if(mimeType.equals(DefaultMIMETypes.DEFAULT_MIME_TYPE))
            		mimeType = ""; // don't need to override it
            	
            	FileBucket fb = new FileBucket(f, true, false, false, false);
            	InsertBlock block = new InsertBlock(fb, new ClientMetadata(mimeType), FreenetURI.EMPTY_CHK_URI);

            	startTime = System.currentTimeMillis();
            	FreenetURI uri = client.insert(block, getCHKOnly);
            	
            	// FIXME depends on CHK's still being renamable
                //uri = uri.setDocName(f.getName());
            	
                outsb.append("URI: "+uri+"\r\n");
            	long endTime = System.currentTimeMillis();
                long sz = f.length();
                double rate = 1000.0 * sz / (endTime-startTime);
                outsb.append("Upload rate: "+rate+" bytes / second\r\n");
            } catch (FileNotFoundException e1) {
                outsb.append("File not found");
            } catch (InserterException e) {
            	outsb.append("Finished insert but: "+e.getMessage());
            	if(e.uri != null) {
            		outsb.append("URI would have been: "+e.uri);
                	long endTime = System.currentTimeMillis();
                    long sz = f.length();
                    double rate = 1000.0 * sz / (endTime-startTime);
                    outsb.append("Upload rate: "+rate+" bytes / second");
            	}
            	if(e.errorCodes != null) {
            		outsb.append("Splitfile errors breakdown:");
            		outsb.append(e.errorCodes.toVerboseString());
            	}
            } catch (Throwable t) {
                outsb.append("Insert threw: "+t);
                t.printStackTrace();
            }
        } else if(uline.startsWith("MAKESSK")) {
        	InsertableClientSSK key = InsertableClientSSK.createRandom(r, "");
        	outsb.append("Insert URI: "+key.getInsertURI().toString(false)+"\r\n");
        	outsb.append("Request URI: "+key.getURI().toString(false)+"\r\n");
        	FreenetURI insertURI = key.getInsertURI().setDocName("testsite");
        	String fixedInsertURI = insertURI.toString(false);
        	outsb.append("Note that you MUST add a filename to the end of the above URLs e.g.:\r\n"+fixedInsertURI+"\r\n");
        	outsb.append("Normally you will then do PUTSSKDIR:<insert URI>#<directory to upload>, for example:\r\nPUTSSKDIR:"+fixedInsertURI+"#directoryToUpload/"+"\r\n");
        	outsb.append("This will then produce a manifest site containing all the files, the default document can be accessed at\r\n"+key.getURI().toString(false)+"testsite/");
        } else if(uline.startsWith("PUTSSK:")) {
        	String cmd = line.substring("PUTSSK:".length());
        	cmd = cmd.trim();
        	if(cmd.indexOf(';') <= 0) {
        		outsb.append("No target URI provided.");
        		outsb.append("PUTSSK:<insert uri>;<url to redirect to>");
        		return false;
        	}
        	String[] split = cmd.split(";");
        	String insertURI = split[0];
        	String targetURI = split[1];
        	outsb.append("Insert URI: "+insertURI);
        	outsb.append("Target URI: "+targetURI);
        	FreenetURI insert = new FreenetURI(insertURI);
        	FreenetURI target = new FreenetURI(targetURI);
        	try {
				FreenetURI result = client.insertRedirect(insert, target);
				outsb.append("Successfully inserted to fetch URI: "+result);
			} catch (InserterException e) {
            	outsb.append("Finished insert but: "+e.getMessage());
            	Logger.normal(this, "Error: "+e, e);
            	if(e.uri != null) {
            		outsb.append("URI would have been: "+e.uri);
            	}
			}
        	
        } else if(uline.startsWith("STATUS")) {
            SimpleFieldSet fs = n.exportPublicFieldSet();
            outsb.append(fs.toString());
            outsb.append(n.getStatus());
            if(Version.buildNumber()<Version.highestSeenBuild){
                outsb.append("The latest version is : "+Version.highestSeenBuild);
            }
        } else if(uline.startsWith("ADDPEER:") || uline.startsWith("CONNECT:")) {
            String key = null;
            if(uline.startsWith("CONNECT:")) {
                key = line.substring("CONNECT:".length());
            } else {
                key = line.substring("ADDPEER:".length());
            }
            while((key.length() > 0) && (key.charAt(0) == ' '))
                key = key.substring(1);
            while((key.length() > 0) && (key.charAt(key.length()-1) == ' '))
                key = key.substring(0, key.length()-2);
            
            String content = null;
            if(key.length() > 0) {
                // Filename
            	BufferedReader in;
                outsb.append("Trying to add peer to node by noderef in "+key+"\r\n");
                File f = new File(key);
                if (f.isFile()) {
                	outsb.append("Given string seems to be a file, loading...\r\n");
                	in = new BufferedReader(new FileReader(f));
                } else {
                	outsb.append("Given string seems to be an URL, loading...\r\n");
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
            if(content == null) return false;
            if(content.equals("")) return false;
            addPeer(content);
        
        } else if(uline.startsWith("NAME:")) {
            outsb.append("Node name currently: "+n.myName);
            String key = line.substring("NAME:".length());
            while((key.length() > 0) && (key.charAt(0) == ' '))
                key = key.substring(1);
            while((key.length() > 0) && (key.charAt(key.length()-1) == ' '))
                key = key.substring(0, key.length()-2);
            outsb.append("New name: "+key);
            
            try{
            	n.config.get("node").getOption("name").setValue(key);
            	Logger.minor(this, "Setting node.name to "+key);
            }catch(Exception e){
            	Logger.error(this, "Error setting node's name");
    		}
    		n.config.store();
        } else if(uline.startsWith("DISABLEPEER:")) {
        	String nodeIdentifier = (line.substring("DISABLEPEER:".length())).trim();
        	if(!havePeer(nodeIdentifier)) {
        		out.write(("no peer for "+nodeIdentifier+"\r\n").getBytes());
        		out.flush();
        		return false;
        	}
        	if(disablePeer(nodeIdentifier)) {
        		outsb.append("disable succeeded for "+nodeIdentifier);
        	} else {
        		outsb.append("disable failed for "+nodeIdentifier);
        	}
        	outsb.append("\r\n");
        } else if(uline.startsWith("ENABLEPEER:")) {
        	String nodeIdentifier = (line.substring("ENABLEPEER:".length())).trim();
        	if(!havePeer(nodeIdentifier)) {
        		out.write(("no peer for "+nodeIdentifier+"\r\n").getBytes());
        		out.flush();
        		return false;
        	}
        	if(enablePeer(nodeIdentifier)) {
        		outsb.append("enable succeeded for "+nodeIdentifier);
        	} else {
        		outsb.append("enable failed for "+nodeIdentifier);
        	}
        	outsb.append("\r\n");
		} else if(uline.startsWith("SETPEERLISTENONLY:")) {
			String nodeIdentifier = (line.substring("SETPEERLISTENONLY:".length())).trim();
        	if(!havePeer(nodeIdentifier)) {
        		out.write(("no peer for "+nodeIdentifier+"\r\n").getBytes());
        		out.flush();
        		return false;
        	}
			PeerNode pn = n.getPeerNode(nodeIdentifier);
        	if(pn == null) {
        		out.write(("n.getPeerNode() failed to get peer details for "+nodeIdentifier+"\r\n\r\n").getBytes());
        		out.flush();
        		return false;
        	}
			pn.setListenOnly(true);
			outsb.append("set ListenOnly suceeded for "+nodeIdentifier+"\r\n");
		} else if(uline.startsWith("UNSETPEERLISTENONLY:")) {
			String nodeIdentifier = (line.substring("UNSETPEERLISTENONLY:".length())).trim();
        	if(!havePeer(nodeIdentifier)) {
        		out.write(("no peer for "+nodeIdentifier+"\r\n").getBytes());
        		out.flush();
        		return false;
        	}
			PeerNode pn = n.getPeerNode(nodeIdentifier);
        	if(pn == null) {
        		out.write(("n.getPeerNode() failed to get peer details for "+nodeIdentifier+"\r\n\r\n").getBytes());
        		out.flush();
        		return false;
        	}
			pn.setListenOnly(false);
			outsb.append("unset ListenOnly suceeded for "+nodeIdentifier+"\r\n");
        } else if(uline.startsWith("HAVEPEER:")) {
        	String nodeIdentifier = (line.substring("HAVEPEER:".length())).trim();
        	if(havePeer(nodeIdentifier)) {
        		outsb.append("true for "+nodeIdentifier);
        	} else {
        		outsb.append("false for "+nodeIdentifier);
        	}
        	outsb.append("\r\n");
        } else if(uline.startsWith("REMOVEPEER:") || uline.startsWith("DISCONNECT:")) {
        	String nodeIdentifier = null;
        	if(uline.startsWith("DISCONNECT:")) {
        		nodeIdentifier = line.substring("DISCONNECT:".length());
        	} else {
        		nodeIdentifier = line.substring("REMOVEPEER:".length());
        	}
        	if(removePeer(nodeIdentifier)) {
        		outsb.append("peer removed for "+nodeIdentifier);
        	} else {
        		outsb.append("peer removal failed for "+nodeIdentifier);
        	}
        	outsb.append("\r\n");
        } else if(uline.startsWith("PEER:")) {
        	String nodeIdentifier = (line.substring("PEER:".length())).trim();
        	if(!havePeer(nodeIdentifier)) {
        		out.write(("no peer for "+nodeIdentifier+"\r\n").getBytes());
        		out.flush();
        		return false;
        	}
        	PeerNode pn = n.getPeerNode(nodeIdentifier);
        	if(pn == null) {
        		out.write(("n.getPeerNode() failed to get peer details for "+nodeIdentifier+"\r\n\r\n").getBytes());
        		out.flush();
        		return false;
        	}
        	SimpleFieldSet fs = pn.exportFieldSet();
        	outsb.append(fs.toString());
        } else if(uline.startsWith("PEERWMD:")) {
        	String nodeIdentifier = (line.substring("PEERWMD:".length())).trim();
        	if(!havePeer(nodeIdentifier)) {
        		out.write(("no peer for "+nodeIdentifier+"\r\n").getBytes());
        		out.flush();
        		return false;
        	}
        	PeerNode pn = n.getPeerNode(nodeIdentifier);
        	if(pn == null) {
        		out.write(("n.getPeerNode() failed to get peer details for "+nodeIdentifier+"\r\n\r\n").getBytes());
        		out.flush();
        		return false;
        	}
        	SimpleFieldSet fs = pn.exportFieldSet();
        	SimpleFieldSet meta = pn.exportMetadataFieldSet();
        	if(!meta.isEmpty())
        	 	fs.put("metadata", meta);
        	outsb.append(fs.toString());
        } else if(uline.startsWith("PEERS")) {
        	outsb.append(n.getTMCIPeerList());
        	outsb.append("PEERS done.\r\n");
        } else if(uline.startsWith("PLUGLOAD:")) {
        	if (line.substring("PLUGLOAD:".length()).trim().equals("?")) {
        		outsb.append("  PLUGLOAD: pkg.Class                  - Load plugin from current classpath");        		
        		outsb.append("  PLUGLOAD: pkg.Class@file:<filename>  - Load plugin from file");
        		outsb.append("  PLUGLOAD: pkg.Class@http://...       - Load plugin from online file");
        		outsb.append("  PLUGLOAD:         *@...              - Load plugin from manifest in given jarfile");
        		outsb.append("");
        		outsb.append("If the filename/url ends with \".url\", it" +
        				" is treated as a link, meaning that the first line is" +
        				" the accual URL. Else it is loaded as classpath and" +
        				" the class it loaded from it (meaning the file could" +
        				" be either a jar-file or a class-file).");
        		outsb.append("");
        		outsb.append("  PLUGLOAD: pkg.Class*  - Load newest version of plugin from http://downloads.freenetproject.org/alpha/plugins/");        		
        		outsb.append("");
        		
        	} else
        		n.pluginManager.startPlugin(line.substring("PLUGLOAD:".length()).trim());
            //outsb.append("PLUGLOAD: <pkg.classname>[(@<URI to jarfile.jar>|<<URI to file containing real URI>|* (will load from freenets pluginpool))] - Load plugin.");
        } else if(uline.startsWith("PLUGLIST")) {
        	outsb.append(n.pluginManager.dumpPlugins());
        } else if(uline.startsWith("PLUGKILL:")) {
        	n.pluginManager.killPlugin(line.substring("PLUGKILL:".length()).trim());
        } else {
        	if(uline.length() > 0)
        		printHeader(out);
        }
        outsb.append("\r\n");
        out.write(outsb.toString().getBytes());
        out.flush();
        return false;
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
                //   Skip unreadable files and dirs
		//   Skip files nonexistant (dangling symlinks) - check last 
	        if (filelist[i].canRead() && filelist[i].exists()) {   	 
    		if (filelist[i].isFile()) {
    			File f = filelist[i];
    			
    			FileBucket bucket = new FileBucket(f, true, false, false, false);
    			
    			ret.put(f.getName(), bucket);
    		} else if(filelist[i].isDirectory()) {
    			HashMap subdir = makeBucketsByName(directory + filelist[i].getName());
    			ret.put(filelist[i].getName(), subdir);
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
                                    if((c == '.') || Character.isLetterOrDigit(c)) {
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
            sb.append(line).append("\r\n");
            if(breakflag) break;
        }
        return sb.toString();
    }

    /**
     * Add a peer to the node, given its reference.
     */
    private void addPeer(String content) {
        SimpleFieldSet fs;
        System.out.println("Connecting to:\r\n"+content);
        try {
            fs = new SimpleFieldSet(content, true);
        } catch (IOException e) {
            System.err.println("Did not parse: "+e);
            e.printStackTrace();
            return;
        }
        PeerNode pn;
        try {
            pn = new PeerNode(fs, n, false);
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
	 * Disable connecting to a peer given its ip and port, name or identity, as a String
	 * Report peer success as boolean
	 */
	private boolean disablePeer(String nodeIdentifier) {
		PeerNode[] pn = n.peers.myPeers;
		for(int i=0;i<pn.length;i++)
		{
			Peer peer = pn[i].getDetectedPeer();
			String nodeIpAndPort = "";
			if(peer != null) {
				nodeIpAndPort = peer.toString();
			}
			String name = pn[i].myName;
			String identity = pn[i].getIdentityString();
			if(identity.equals(nodeIdentifier) || nodeIpAndPort.equals(nodeIdentifier) || name.equals(nodeIdentifier)) {
				pn[i].disablePeer();
				return true;
			}
		}
		return false;
	}

	/**
	 * Enable connecting to a peer given its ip and port, name or identity, as a String
	 * Report peer success as boolean
	 */
	private boolean enablePeer(String nodeIdentifier) {
		PeerNode[] pn = n.peers.myPeers;
		for(int i=0;i<pn.length;i++)
		{
			Peer peer = pn[i].getDetectedPeer();
			String nodeIpAndPort = "";
			if(peer != null) {
				nodeIpAndPort = peer.toString();
			}
			String name = pn[i].myName;
			String identity = pn[i].getIdentityString();
			if(identity.equals(nodeIdentifier) || nodeIpAndPort.equals(nodeIdentifier) || name.equals(nodeIdentifier)) {
				pn[i].enablePeer();
				return true;
			}
		}
		return false;
	}
    
    /**
     * Check for a peer of the node given its ip and port, name or identity, as a String
     * Report peer existence as boolean
     */
    private boolean havePeer(String nodeIdentifier) {
    	PeerNode[] pn = n.peers.myPeers;
    	for(int i=0;i<pn.length;i++)
    	{
    		Peer peer = pn[i].getDetectedPeer();
    		String nodeIpAndPort = "";
    		if(peer != null) {
    			nodeIpAndPort = peer.toString();
    		}
    		String name = pn[i].myName;
    		String identity = pn[i].getIdentityString();
    		if(identity.equals(nodeIdentifier) || nodeIpAndPort.equals(nodeIdentifier) || name.equals(nodeIdentifier))
    		{
    			return true;
    		}
    	}
    	return false;
    }
    
    /**
     * Remove a peer from the node given its ip and port, name or identity, as a String
     * Report peer removal successfulness as boolean
     */
    private boolean removePeer(String nodeIdentifier) {
    	System.out.println("Removing peer from node for: "+nodeIdentifier);
    	PeerNode[] pn = n.peers.myPeers;
    	for(int i=0;i<pn.length;i++)
    	{
    		Peer peer = pn[i].getDetectedPeer();
    		String nodeIpAndPort = "";
    		if(peer != null) {
        		nodeIpAndPort = peer.toString();
    		}
    		String name = pn[i].myName;
    		String identity = pn[i].getIdentityString();
    		if(identity.equals(nodeIdentifier) || nodeIpAndPort.equals(nodeIdentifier) || name.equals(nodeIdentifier))
    		{
    			n.removeDarknetConnection(pn[i]);
    			return true;
    		}
    	}
    	System.out.println("No node in peers list for: "+nodeIdentifier);
    	return false;
    }

    private String sanitize(String fnam) {
    	if(fnam == null) return "";
        StringBuffer sb = new StringBuffer(fnam.length());
        for(int i=0;i<fnam.length();i++) {
            char c = fnam.charAt(i);
            if(Character.isLetterOrDigit(c) || (c == '-') || (c == '.'))
                sb.append(c);
        }
        return sb.toString();
    }

}
