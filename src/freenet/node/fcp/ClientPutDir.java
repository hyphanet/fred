/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;

import com.db4o.ObjectContainer;

import freenet.client.DefaultMIMETypes;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.async.ClientGetter;
import freenet.client.async.ClientRequester;
import freenet.client.async.ManifestElement;
import freenet.client.async.ManifestPutter;
import freenet.clients.fcp.ClientRequest;
import freenet.clients.fcp.IdentifierCollisionException;
import freenet.clients.fcp.NotAllowedException;
import freenet.clients.fcp.PersistentRequestClient;
import freenet.keys.FreenetURI;
import freenet.node.NodeClientCore;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.io.FileBucket;

public class ClientPutDir extends ClientPutBase {

	private HashMap<String, Object> manifestElements;
	private ManifestPutter putter;
	private short manifestPutterType;
	private final String defaultName;
	private final long totalSize;
	private final int numberOfFiles;
	private final boolean wasDiskPut;
	
	private static volatile boolean logMINOR;
	private final byte[] overrideSplitfileCryptoKey;
	
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
    @Override
    public ClientRequest migrate(PersistentRequestClient newClient, ObjectContainer container,
            NodeClientCore core) throws IdentifierCollisionException, NotAllowedException,
            IOException {
        Logger.error(this, "Not migrating site upload");
        return null;
    }
    
	/**
	 * zero arg c'tor for db4o on jamvm
	 */
	@SuppressWarnings("unused")
	protected ClientPutDir() {
	    throw new UnsupportedOperationException();
	}

	private HashMap<String, Object> makeDiskDirManifest(File dir, String prefix, boolean allowUnreadableFiles, boolean includeHiddenFiles) throws FileNotFoundException {

		HashMap<String, Object> map = new HashMap<String, Object>();
		File[] files = dir.listFiles();
		
		if(files == null)
			throw new IllegalArgumentException("No such directory");

		for (File f : files) {
			
    		if(f.isHidden() && !includeHiddenFiles) continue;

			if (f.exists() && f.canRead()) {
				if(f.isFile()) {
					FileBucket bucket = new FileBucket(f, true, false, false, false, false);
					if(logMINOR)
						Logger.minor(this, "Add file : " + f.getAbsolutePath());
					
					map.put(f.getName(), new ManifestElement(f.getName(), prefix + f.getName(), bucket, DefaultMIMETypes.guessMIMEType(f.getName(), true), f.length()));
				} else if(f.isDirectory()) {
					if(logMINOR)
						Logger.minor(this, "Add dir : " + f.getAbsolutePath());
					
					map.put(f.getName(), makeDiskDirManifest(f, prefix + f.getName() + "/", allowUnreadableFiles, includeHiddenFiles));
				} else {
					if(!allowUnreadableFiles)
						throw new FileNotFoundException("Not a file and not a directory : " + f);
				}
			} else if (!allowUnreadableFiles)
				throw new FileNotFoundException("The file does not exist or is unreadable : " + f);
			
		}

		return map;
	}
	
	@Override
	protected void freeData(ObjectContainer container) {
		if(logMINOR) Logger.minor(this, "freeData() on "+this+" persistence type = "+persistenceType);
		synchronized(this) {
			if(manifestElements == null) {
				if(logMINOR)
					Logger.minor(this, "manifestElements = "+manifestElements +
							(persistenceType != PERSIST_FOREVER ? "" : (" dir.active="+container.ext().isActive(this))), new Exception("error"));
				return;
			}
		}
		if(logMINOR) Logger.minor(this, "freeData() more on "+this+" persistence type = "+persistenceType);
		// We have to commit everything, so activating everything here doesn't cost us much memory...?
		if(persistenceType == PERSIST_FOREVER) {
			container.deactivate(manifestElements, 1); // Must deactivate before activating: If it has been activated to depth 1 (empty map) at some point it will fail to activate to depth 2 (with contents). See http://tracker.db4o.com/browse/COR-1582
			container.activate(manifestElements, Integer.MAX_VALUE);
		}
		freeData(manifestElements, container);
		manifestElements = null;
		if(persistenceType == PERSIST_FOREVER) container.store(this);
	}
	
	@SuppressWarnings("unchecked")
	private void freeData(HashMap<String, Object> manifestElements, ObjectContainer container) {
		if(logMINOR) Logger.minor(this, "freeData() inner on "+this+" persistence type = "+persistenceType+" size = "+manifestElements.size());
		for(Object o: manifestElements.values()) {
			if(o instanceof HashMap) {
				freeData((HashMap<String, Object>) o, container);
			} else {
				ManifestElement e = (ManifestElement) o;
				if(logMINOR) Logger.minor(this, "Freeing "+e);
				e.freeData();
			}
		}
		if(persistenceType == PERSIST_FOREVER) container.delete(manifestElements);
	}

	@Override
	protected ClientRequester getClientRequest() {
		return putter;
	}

	@Override
	protected String getTypeName() {
		return "PUTDIR";
	}

	@Override
	public boolean hasSucceeded() {
		return succeeded;
	}

	public FreenetURI getFinalURI(ObjectContainer container) {
		if(persistenceType == PERSIST_FOREVER)
			container.activate(generatedURI, 5);
		return generatedURI;
	}

	public int getNumberOfFiles() {
		return numberOfFiles;
	}

	public long getTotalDataSize() {
		return totalSize;
	}

	public void onFailure(FetchException e, ClientGetter state, ObjectContainer container) {}

	public void onSuccess(FetchResult result, ClientGetter state, ObjectContainer container) {}

}
