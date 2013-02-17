/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;

import com.db4o.ObjectContainer;

import freenet.client.DefaultMIMETypes;
import freenet.client.async.ManifestElement;
import freenet.node.Node;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.SimpleFieldSet;
import freenet.support.api.BucketFactory;
import freenet.support.io.FileBucket;

/**
 * Insert a directory from disk as a manifest.
 * 
 * ClientPutDiskDirMessage
 * < generic fields from ClientPutDirMessage >
 * Filename=<filename>
 * AllowUnreadableFiles=<unless true, any unreadable files cause the whole request to fail>
 * End
 * 
 * FIXME this should use the same code as makeDiskDirManifest does for internal
 * directory inserts.
 */
public class ClientPutDiskDirMessage extends ClientPutDirMessage {

	public final static String NAME = "ClientPutDiskDir";
	
	final File dirname;
	final boolean allowUnreadableFiles;
	final boolean includeHiddenFiles;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	public ClientPutDiskDirMessage(SimpleFieldSet fs) throws MessageInvalidException {
		super(fs);
		allowUnreadableFiles = fs.getBoolean("AllowUnreadableFiles", false);
		includeHiddenFiles = fs.getBoolean("includeHiddenFiles", false);
		String fnam = fs.get("Filename");
		if(fnam == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "Filename missing", identifier, global);
		dirname = new File(fnam);
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		if(!handler.server.core.allowUploadFrom(dirname))
			throw new MessageInvalidException(ProtocolErrorMessage.ACCESS_DENIED, "Not allowed to upload from "+dirname, identifier, global);
		// Create a directory listing of Buckets of data, mapped to ManifestElement's.
		// Directories are sub-HashMap's.
		HashMap<String, Object> buckets = makeBucketsByName(dirname, "");
		handler.startClientPutDir(this, buckets, true);
	}

    /**
     * Create a map of String -> Bucket for every file in a directory
     * and its subdirs.
     * @throws MessageInvalidException 
     */
    private HashMap<String, Object> makeBucketsByName(File thisdir, String prefix) throws MessageInvalidException {
    	
    	if(logMINOR)
    		Logger.minor(this, "Listing directory: "+thisdir);
    	
    	HashMap<String, Object> ret = new HashMap<String, Object>();
    	
    	File filelist[] = thisdir.listFiles();
    	if(filelist == null)
    		throw new MessageInvalidException(ProtocolErrorMessage.FILE_NOT_FOUND, "No such directory!", identifier, global);
    	for(int i = 0 ; i < filelist.length ; i++) {
    		if(filelist[i].isHidden() && !includeHiddenFiles) continue;
                //   Skip unreadable files and dirs
		//   Skip files nonexistant (dangling symlinks) - check last 
	        if (filelist[i].canRead() && filelist[i].exists()) {
	        	if (filelist[i].isFile()) {
	        		File f = filelist[i];
	        		
	        		FileBucket bucket = new FileBucket(f, true, false, false, false, false);
	        		
	        		ret.put(f.getName(), new ManifestElement(f.getName(), prefix + f.getName(), bucket, DefaultMIMETypes.guessMIMEType(f.getName(), true), f.length()));
	        	} else if(filelist[i].isDirectory()) {
	        		HashMap<String, Object> subdir = makeBucketsByName(new File(thisdir, filelist[i].getName()), prefix
					        + filelist[i].getName() + '/');
	        		ret.put(filelist[i].getName(), subdir);
	        	} else if(!allowUnreadableFiles) {
	        		throw new MessageInvalidException(ProtocolErrorMessage.FILE_NOT_FOUND, "Not directory and not file: "+filelist[i], identifier, global);
	        	}
	        } else {
	        	if(!allowUnreadableFiles)
	        		throw new MessageInvalidException(ProtocolErrorMessage.FILE_NOT_FOUND, "Not readable or doesn't exist: "+filelist[i], identifier, global);
	        }
    	}
    	return ret;
	}

	@Override
	long dataLength() {
		return 0;
	}

	String getIdentifier() {
		return identifier;
	}

	@Override
	public void readFrom(InputStream is, BucketFactory bf, FCPServer server) throws IOException, MessageInvalidException {
		// Do nothing
	}

	@Override
	protected void writeData(OutputStream os) throws IOException {
		// Do nothing
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		container.delete(dirname);
		container.delete(this);
	}

}
