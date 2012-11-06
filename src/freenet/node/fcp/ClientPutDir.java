/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.File;
import java.io.FileNotFoundException;
import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.Iterator;

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
import freenet.client.async.ManifestPutter;
import freenet.client.async.SimpleManifestPutter;
import freenet.client.async.DefaultManifestPutter;
import freenet.keys.FreenetURI;
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

	/**
	 * zero arg c'tor for db4o on jamvm
	 */
	@SuppressWarnings("unused")
	private ClientPutDir() {
		wasDiskPut = false;
		totalSize = 0;
		numberOfFiles = 0;
		defaultName = null;
		overrideSplitfileCryptoKey = null;
	}

	public ClientPutDir(FCPConnectionHandler handler, ClientPutDirMessage message, 
			HashMap<String, Object> manifestElements, boolean wasDiskPut, FCPServer server, ObjectContainer container) throws IdentifierCollisionException, MalformedURLException {
		super(checkEmptySSK(message.uri, message.targetFilename != null ? message.targetFilename : "site", server.core.clientContext), message.identifier, message.verbosity, null,
				handler, message.priorityClass, message.persistenceType, message.clientToken,
				message.global, message.getCHKOnly, message.dontCompress, message.localRequestOnly, message.maxRetries, message.earlyEncode, message.canWriteClientCache, message.forkOnCacheable, message.compressorDescriptor, message.extraInsertsSingleBlock, message.extraInsertsSplitfileHeaderBlock, message.realTimeFlag, message.compatibilityMode, server, container);
		logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
		this.wasDiskPut = wasDiskPut;
		this.overrideSplitfileCryptoKey = message.overrideSplitfileCryptoKey;
		
		// objectOnNew is called once, objectOnUpdate is never called, yet manifestElements get blanked anyway!
		
		this.manifestElements = new HashMap<String,Object>();
		this.manifestElements.putAll(manifestElements);
		
//		this.manifestElements = manifestElements;
		
//		this.manifestElements = new HashMap<String, Object>();
//		this.manifestElements.putAll(manifestElements);
		this.defaultName = message.defaultName;
		this.manifestPutterType = message.manifestPutterType;
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
	public ClientPutDir(FCPClient client, FreenetURI uri, String identifier, int verbosity, short priorityClass, short persistenceType, String clientToken, boolean getCHKOnly, boolean dontCompress, int maxRetries, File dir, String defaultName, boolean allowUnreadableFiles, boolean global, boolean earlyEncode, boolean canWriteClientCache, boolean forkOnCacheable, int extraInsertsSingleBlock, int extraInsertsSplitfileHeaderBlock, boolean realTimeFlag, byte[] overrideSplitfileCryptoKey, FCPServer server, ObjectContainer container) throws FileNotFoundException, IdentifierCollisionException, MalformedURLException {
		super(checkEmptySSK(uri, "site", server.core.clientContext), identifier, verbosity , null, null, client, priorityClass, persistenceType, clientToken, global, getCHKOnly, dontCompress, maxRetries, earlyEncode, canWriteClientCache, forkOnCacheable, false, extraInsertsSingleBlock, extraInsertsSplitfileHeaderBlock, realTimeFlag, null, InsertContext.CompatibilityMode.COMPAT_CURRENT, server, container);
		wasDiskPut = true;
		this.overrideSplitfileCryptoKey = overrideSplitfileCryptoKey;
		logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
		this.manifestElements = makeDiskDirManifest(dir, "", allowUnreadableFiles);
		this.defaultName = defaultName;
		this.manifestPutterType = ManifestPutter.MANIFEST_SIMPLEPUTTER;
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

	public ClientPutDir(FCPClient client, FreenetURI uri, String identifier, int verbosity, short priorityClass, short persistenceType, String clientToken, boolean getCHKOnly, boolean dontCompress, int maxRetries, HashMap<String, Object> elements, String defaultName, boolean global, boolean earlyEncode, boolean canWriteClientCache, boolean forkOnCacheable, int extraInsertsSingleBlock, int extraInsertsSplitfileHeaderBlock, boolean realTimeFlag, byte[] overrideSplitfileCryptoKey, FCPServer server, ObjectContainer container) throws IdentifierCollisionException, MalformedURLException {
		super(checkEmptySSK(uri, "site", server.core.clientContext), identifier, verbosity , null, null, client, priorityClass, persistenceType, clientToken, global, getCHKOnly, dontCompress, maxRetries, earlyEncode, canWriteClientCache, forkOnCacheable, false, extraInsertsSingleBlock, extraInsertsSplitfileHeaderBlock, realTimeFlag, null, InsertContext.CompatibilityMode.COMPAT_CURRENT, server, container);
		wasDiskPut = false;
		this.overrideSplitfileCryptoKey = overrideSplitfileCryptoKey;
		logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
		this.manifestElements = elements;
		this.defaultName = defaultName;
		this.manifestPutterType = ManifestPutter.MANIFEST_SIMPLEPUTTER;
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
		switch(manifestPutterType) {
		case ManifestPutter.MANIFEST_DEFAULTPUTTER:
			putter = new DefaultManifestPutter(this,
					manifestElements, priorityClass, uri, defaultName, ctx, getCHKOnly,
					lowLevelClient,
					earlyEncode, persistenceType == PERSIST_FOREVER, overrideSplitfileCryptoKey, container, context);
			break;
		default:
			putter = new SimpleManifestPutter(this, 
					manifestElements, priorityClass, uri, defaultName, ctx, getCHKOnly,
					lowLevelClient,
					earlyEncode, persistenceType == PERSIST_FOREVER, overrideSplitfileCryptoKey, container, context);
		}
	}

	@Override
	public void start(ObjectContainer container, ClientContext context) {
		if(finished) return;
		if(started) return;
		try {
			if(persistenceType == PERSIST_FOREVER)
				container.activate(putter, 1);
			if(putter != null)
				putter.start(container, context);

			started = true;
			if(client != null) {
				RequestStatusCache cache = client.getRequestStatusCache();
				if(cache != null) {
					cache.updateStarted(identifier, true);
				}
			}
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
		Iterator<Object> i = manifestElements.values().iterator();
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
			container.activate(uri, 5);
			container.activate(ctx, 1);
			container.activate(manifestElements, 5);
		}
		// FIXME: remove debug code
		if (lowLevelClient == null)
			Logger.error(this, "lowLevelClient == null", new Exception("error"));
		if (putter == null)
			Logger.error(this, "putter == null", new Exception("error"));
		// FIXME end
		return new PersistentPutDir(identifier, publicURI, uri, verbosity, priorityClass,
				persistenceType, global, defaultName, manifestElements, clientToken, started, ctx.maxInsertRetries, ctx.dontCompress, ctx.compressorDescriptor, wasDiskPut, isRealTime(), putter != null ? putter.getSplitfileCryptoKey() : null, container);
	}
	
	private boolean isRealTime() {
		// FIXME: remove debug code
		if (lowLevelClient == null) {
			// This can happen but only due to data corruption - old databases on which various bugs have resulted in it getting deleted, and also possibly failed deletions.
			Logger.error(this, "lowLevelClient == null", new Exception("error"));
			return false;
		}
		return lowLevelClient.realTimeFlag();
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
	public boolean restart(ObjectContainer container, ClientContext context, final boolean disableFilterData) {
		if(!canRestart()) return false;
		setVarsRestart(container);
		if(client != null) {
			RequestStatusCache cache = client.getRequestStatusCache();
			if(cache != null) {
				cache.updateStarted(identifier, false);
			}
		}
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

	@Override
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
	
	@Override
	RequestStatus getStatus(ObjectContainer container) {
		FreenetURI finalURI = getFinalURI(container);
		if(finalURI != null) finalURI = getFinalURI(container).clone();
		int failureCode = (short)-1;
		String failureReasonShort = null;
		String failureReasonLong = null;
		if(putFailedMessage != null) {
			if(persistenceType == PERSIST_FOREVER)
				container.activate(putFailedMessage, 5);
			failureCode = putFailedMessage.code;
			failureReasonShort = putFailedMessage.getShortFailedMessage();
			failureReasonShort = putFailedMessage.getLongFailedMessage();
			if(persistenceType == PERSIST_FOREVER)
				container.deactivate(putFailedMessage, 5);
		}
		
		int total=0, min=0, fetched=0, fatal=0, failed=0;
		boolean totalFinalized = false;
		
		if(progressMessage != null) {
			if(persistenceType == PERSIST_FOREVER)
				container.activate(progressMessage, 2);
			if(progressMessage instanceof SimpleProgressMessage) {
				SimpleProgressMessage msg = (SimpleProgressMessage)progressMessage;
				total = (int) msg.getTotalBlocks();
				min = (int) msg.getMinBlocks();
				fetched = (int) msg.getFetchedBlocks();
				fatal = (int) msg.getFatalyFailedBlocks();
				failed = (int) msg.getFailedBlocks();
				totalFinalized = msg.isTotalFinalized();
			}
		}
		
		FreenetURI targetURI = uri;
		if(persistenceType == PERSIST_FOREVER) {
			container.activate(targetURI, Integer.MAX_VALUE);
			targetURI = targetURI.clone();
		}
		
		return new UploadDirRequestStatus(identifier, persistenceType, started, finished, 
				succeeded, total, min, fetched, fatal, failed, totalFinalized, 
				lastActivity, priorityClass, finalURI, targetURI, failureCode,
				failureReasonShort, failureReasonLong, totalSize, numberOfFiles);
	}
	

}
