/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Vector;

import com.db4o.ObjectContainer;

import freenet.client.DefaultMIMETypes;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientContext;
import freenet.client.async.ClientGetter;
import freenet.client.async.ClientRequester;
import freenet.client.async.ManifestElement;
import freenet.client.async.SimpleManifestPutter;
import freenet.keys.FreenetURI;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.io.CannotCreateFromFieldSetException;
import freenet.support.io.FileBucket;
import freenet.support.io.SerializableToFieldSetBucketUtil;

public class ClientPutDir extends ClientPutBase {

	private HashMap<String, Object> manifestElements;
	private SimpleManifestPutter putter;
	private final String defaultName;
	private final long totalSize;
	private final int numberOfFiles;
	private final boolean wasDiskPut;
	
	private static volatile boolean logMINOR;
	
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	public ClientPutDir(FCPConnectionHandler handler, ClientPutDirMessage message, 
			HashMap<String, Object> manifestElements, boolean wasDiskPut, FCPServer server, ObjectContainer container) throws IdentifierCollisionException, MalformedURLException {
		super(checkEmptySSK(message.uri, "site", server.core.clientContext), message.identifier, message.verbosity, null,
				handler, message.priorityClass, message.persistenceType, message.clientToken,
				message.global, message.getCHKOnly, message.dontCompress, message.localRequestOnly, message.maxRetries, message.earlyEncode, message.canWriteClientCache, message.forkOnCacheable, message.compressorDescriptor, message.extraInsertsSingleBlock, message.extraInsertsSplitfileHeaderBlock, message.compatibilityMode, server, container);
		logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
		this.wasDiskPut = wasDiskPut;
		
		// objectOnNew is called once, objectOnUpdate is never called, yet manifestElements get blanked anyway!
		
		this.manifestElements = new HashMap<String,Object>();
		this.manifestElements.putAll(manifestElements);
		
//		this.manifestElements = manifestElements;
		
//		this.manifestElements = new HashMap<String, Object>();
//		this.manifestElements.putAll(manifestElements);
		this.defaultName = message.defaultName;
		makePutter(container, server.core.clientContext);
		if(putter != null) {
			numberOfFiles = putter.countFiles();
			totalSize = putter.totalSize();
		} else {
			numberOfFiles = -1;
			totalSize = -1;
		}
		if(logMINOR) Logger.minor(this, "Putting dir "+identifier+" : "+priorityClass);
	}

	/**
	 * Fproxy
	*	Puts a disk dir
	 * @throws InsertException 
	*/
	public ClientPutDir(FCPClient client, FreenetURI uri, String identifier, int verbosity, short priorityClass, short persistenceType, String clientToken, boolean getCHKOnly, boolean dontCompress, int maxRetries, File dir, String defaultName, boolean allowUnreadableFiles, boolean global, boolean earlyEncode, boolean canWriteClientCache, boolean forkOnCacheable, int extraInsertsSingleBlock, int extraInsertsSplitfileHeaderBlock, FCPServer server, ObjectContainer container) throws FileNotFoundException, IdentifierCollisionException, MalformedURLException {
		super(checkEmptySSK(uri, "site", server.core.clientContext), identifier, verbosity , null, null, client, priorityClass, persistenceType, clientToken, global, getCHKOnly, dontCompress, maxRetries, earlyEncode, canWriteClientCache, forkOnCacheable, false, extraInsertsSingleBlock, extraInsertsSplitfileHeaderBlock, null, InsertContext.CompatibilityMode.COMPAT_CURRENT, server, container);
		wasDiskPut = true;
		logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
		this.manifestElements = makeDiskDirManifest(dir, "", allowUnreadableFiles);
		this.defaultName = defaultName;
		makePutter(container, server.core.clientContext);
		if(putter != null) {
			numberOfFiles = putter.countFiles();
			totalSize = putter.totalSize();
		} else {
			numberOfFiles = -1;
			totalSize = -1;
		}
		if(logMINOR) Logger.minor(this, "Putting dir "+identifier+" : "+priorityClass);
	}

	public ClientPutDir(FCPClient client, FreenetURI uri, String identifier, int verbosity, short priorityClass, short persistenceType, String clientToken, boolean getCHKOnly, boolean dontCompress, int maxRetries, HashMap<String, Object> elements, String defaultName, boolean global, boolean earlyEncode, boolean canWriteClientCache, boolean forkOnCacheable, int extraInsertsSingleBlock, int extraInsertsSplitfileHeaderBlock, FCPServer server, ObjectContainer container) throws IdentifierCollisionException, MalformedURLException {
		super(checkEmptySSK(uri, "site", server.core.clientContext), identifier, verbosity , null, null, client, priorityClass, persistenceType, clientToken, global, getCHKOnly, dontCompress, maxRetries, earlyEncode, canWriteClientCache, forkOnCacheable, false, extraInsertsSingleBlock, extraInsertsSplitfileHeaderBlock, null, InsertContext.CompatibilityMode.COMPAT_CURRENT, server, container);
		wasDiskPut = false;
		logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
		this.manifestElements = elements;
		this.defaultName = defaultName;
		makePutter(container, server.core.clientContext);
		if(putter != null) {
			numberOfFiles = putter.countFiles();
			totalSize = putter.totalSize();
		} else {
			numberOfFiles = -1;
			totalSize = -1;
		}
		if(logMINOR) Logger.minor(this, "Putting data from custom buckets "+identifier+" : "+priorityClass);
	}

	@Override
	void register(ObjectContainer container, boolean noTags) throws IdentifierCollisionException {
		if(persistenceType != PERSIST_CONNECTION)
			client.register(this, container);
		if(persistenceType != PERSIST_CONNECTION && !noTags) {
			FCPMessage msg = persistentTagMessage(container);
			client.queueClientRequestMessage(msg, 0, container);
		}
	}
	
	private HashMap<String, Object> makeDiskDirManifest(File dir, String prefix, boolean allowUnreadableFiles) throws FileNotFoundException {

		HashMap<String, Object> map = new HashMap<String, Object>();
		File[] files = dir.listFiles();
		
		if(files == null)
			throw new IllegalArgumentException("No such directory");

		for (File f : files) {

			if (f.exists() && f.canRead()) {
				if(f.isFile()) {
					FileBucket bucket = new FileBucket(f, true, false, false, false, false);
					if(logMINOR)
						Logger.minor(this, "Add file : " + f.getAbsolutePath());
					
					map.put(f.getName(), new ManifestElement(f.getName(), prefix + f.getName(), bucket, DefaultMIMETypes.guessMIMEType(f.getName(), true), f.length()));
				} else if(f.isDirectory()) {
					if(logMINOR)
						Logger.minor(this, "Add dir : " + f.getAbsolutePath());
					
					map.put(f.getName(), makeDiskDirManifest(f, prefix + f.getName() + "/", allowUnreadableFiles));
				} else {
					if(!allowUnreadableFiles)
						throw new FileNotFoundException("Not a file and not a directory : " + f);
				}
			} else if (!allowUnreadableFiles)
				throw new FileNotFoundException("The file does not exist or is unreadable : " + f);
			
		}

		return map;
	}
	
	private void makePutter(ObjectContainer container, ClientContext context) {
		SimpleManifestPutter p;
			p = new SimpleManifestPutter(this, 
					manifestElements, priorityClass, uri, defaultName, ctx, getCHKOnly,
					lowLevelClient,
					earlyEncode, persistenceType == PERSIST_FOREVER, container, context);
		putter = p;
	}



	public ClientPutDir(SimpleFieldSet fs, FCPClient client, FCPServer server, ObjectContainer container) throws PersistenceParseException, IOException {
		super(fs, client, server);
		logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
		SimpleFieldSet files = fs.subset("Files");
		defaultName = fs.get("DefaultName");
		String type = fs.get("PutDirType");
		if(type.equals("disk"))
			wasDiskPut = true;
		else
			wasDiskPut = false;
		// Flattened for disk, sort out afterwards
		int fileCount = 0;
		long size = 0;
		Vector<ManifestElement> v = new Vector<ManifestElement>();
		for(int i=0;;i++) {
			String num = Integer.toString(i);
			SimpleFieldSet subset = files.subset(num);
			if(subset == null) break;
			// Otherwise serialize
			String name = subset.get("Name");
			if(name == null)
				throw new PersistenceParseException("No Name on "+i);
			String contentTypeOverride = subset.get("Metadata.ContentType");
			String uploadFrom = subset.get("UploadFrom");
			Bucket data = null;
			if(logMINOR) Logger.minor(this, "Parsing "+i);
			if(logMINOR) Logger.minor(this, "UploadFrom="+uploadFrom);
			ManifestElement me;
			if((uploadFrom == null) || uploadFrom.equalsIgnoreCase("direct")) {
				long sz = Long.parseLong(subset.get("DataLength"));
				if(!finished) {
					try {
						data = SerializableToFieldSetBucketUtil.create(fs.subset("ReturnBucket"), server.core.random, server.core.persistentTempBucketFactory);
					} catch (CannotCreateFromFieldSetException e) {
						throw new PersistenceParseException("Could not read old bucket for "+identifier+" : "+e, e);
					}
				} else {
					data = null;
				}
				me = new ManifestElement(name, data, contentTypeOverride, sz);
				fileCount++;
			} else if(uploadFrom.equalsIgnoreCase("disk")) {
				long sz = Long.parseLong(subset.get("DataLength"));
				// Disk
				String f = subset.get("Filename");
				if(f == null)
					throw new PersistenceParseException("UploadFrom=disk but no name on "+i);
				File ff = new File(f);
				if(!(ff.exists() && ff.canRead())) {
					Logger.error(this, "File no longer exists, cancelling upload: "+ff);
					throw new IOException("File no longer exists, cancelling upload: "+ff);
				}
				data = new FileBucket(ff, true, false, false, false, false);
				me = new ManifestElement(name, data, contentTypeOverride, sz);
				fileCount++;
			} else if(uploadFrom.equalsIgnoreCase("redirect")) {
				FreenetURI targetURI = new FreenetURI(subset.get("TargetURI"));
				me = new ManifestElement(name, targetURI, contentTypeOverride);
			} else
				throw new PersistenceParseException("Don't know UploadFrom="+uploadFrom);
			v.add(me);
			if((data != null) && (data.size() > 0))
				size += data.size();
		}
		manifestElements = SimpleManifestPutter.unflatten(v);
		SimpleManifestPutter p = null;
			if(!finished)
				p = new SimpleManifestPutter(this, 
						manifestElements, priorityClass, uri, defaultName, ctx, getCHKOnly, 
						lowLevelClient,
						earlyEncode, persistenceType == PERSIST_FOREVER, container, server.core.clientContext);
		putter = p;
		numberOfFiles = fileCount;
		totalSize = size;
		if(persistenceType != PERSIST_CONNECTION) {
			FCPMessage msg = persistentTagMessage(container);
			client.queueClientRequestMessage(msg, 0, container);
		}
	}

	@Override
	public void start(ObjectContainer container, ClientContext context) {
		if(finished) return;
		if(started) return;
		try {
			if(putter != null)
				putter.start(container, context);
			started = true;
			if(logMINOR) Logger.minor(this, "Started "+putter+" for "+this+" persistence="+persistenceType);
			if(persistenceType != PERSIST_CONNECTION && !finished) {
				FCPMessage msg = persistentTagMessage(container);
				client.queueClientRequestMessage(msg, 0, container);
			}
			if(persistenceType == PERSIST_FOREVER)
				container.store(this); // Update
		} catch (InsertException e) {
			started = true;
			onFailure(e, null, container);
		}
	}
	
	@Override
	public void onLostConnection(ObjectContainer container, ClientContext context) {
		if(persistenceType == PERSIST_CONNECTION)
			cancel(container, context);
		// otherwise ignore
	}
	
	@Override
	@SuppressWarnings("unchecked")
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
		Iterator i = manifestElements.values().iterator();
		while(i.hasNext()) {
			Object o = i.next();
			if(o instanceof HashMap) {
				freeData((HashMap<String, Object>) o, container);
			} else {
				ManifestElement e = (ManifestElement) o;
				if(logMINOR) Logger.minor(this, "Freeing "+e);
				e.freeData(container, persistenceType == PERSIST_FOREVER);
			}
		}
		if(persistenceType == PERSIST_FOREVER) container.delete(manifestElements);
	}

	@Override
	protected ClientRequester getClientRequest() {
		return putter;
	}

	@Override
	protected FCPMessage persistentTagMessage(ObjectContainer container) {
		if(persistenceType == PERSIST_FOREVER) {
			container.activate(publicURI, 5);
			container.activate(ctx, 1);
			container.activate(manifestElements, 5);
		}
		return new PersistentPutDir(identifier, publicURI, verbosity, priorityClass,
				persistenceType, global, defaultName, manifestElements, clientToken, started, ctx.maxInsertRetries, wasDiskPut, container);
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

	@Override
	public boolean canRestart() {
		if(!finished) {
			Logger.minor(this, "Cannot restart because not finished for "+identifier);
			return false;
		}
		if(succeeded) {
			Logger.minor(this, "Cannot restart because succeeded for "+identifier);
			return false;
		}
		return true;
	}

	@Override
	public boolean restart(boolean filterData, ObjectContainer container, ClientContext context) {
		if(!canRestart()) return false;
		setVarsRestart(container);
		makePutter(container, context);
		start(container, context);
		return true;
	}

	public void onFailure(FetchException e, ClientGetter state, ObjectContainer container) {}

	public void onSuccess(FetchResult result, ClientGetter state, ObjectContainer container) {}
	
	@Override
	public void onSuccess(BaseClientPutter state, ObjectContainer container) {
		super.onSuccess(state, container);
	}
	
	@Override
	public void onFailure(InsertException e, BaseClientPutter state, ObjectContainer container) {
		super.onFailure(e, state, container);
	}

	public void onRemoveEventProducer(ObjectContainer container) {
		// Do nothing, we called the removeFrom().
	}
	
	@Override
	public void requestWasRemoved(ObjectContainer container, ClientContext context) {
		if(persistenceType == PERSIST_FOREVER) {
			container.activate(putter, 1);
			putter.removeFrom(container, context);
			putter = null;
		}
		super.requestWasRemoved(container, context);
	}

	@Override
	protected void onStartCompressing() {
		// Ignore
	}

	@Override
	protected void onStopCompressing() {
		// Ignore
	}
}
