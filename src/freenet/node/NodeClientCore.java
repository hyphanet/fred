package freenet.node;

import java.io.File;
import java.io.IOException;
import java.net.URI;

import freenet.client.ArchiveManager;
import freenet.client.HighLevelSimpleClient;
import freenet.client.HighLevelSimpleClientImpl;
import freenet.client.InserterContext;
import freenet.client.async.BackgroundBlockEncoder;
import freenet.client.async.HealingQueue;
import freenet.client.async.SimpleHealingQueue;
import freenet.client.async.USKManager;
import freenet.client.events.SimpleEventProducer;
import freenet.clients.http.BookmarkManager;
import freenet.clients.http.FProxyToadlet;
import freenet.clients.http.SimpleToadletServer;
import freenet.clients.http.filter.FilterCallback;
import freenet.clients.http.filter.FoundURICallback;
import freenet.clients.http.filter.GenericReadFilterCallback;
import freenet.config.Config;
import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.crypt.RandomSource;
import freenet.io.xfer.AbortedException;
import freenet.io.xfer.PartiallyReceivedBlock;
import freenet.keys.CHKBlock;
import freenet.keys.CHKVerifyException;
import freenet.keys.ClientCHK;
import freenet.keys.ClientCHKBlock;
import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.ClientSSK;
import freenet.keys.ClientSSKBlock;
import freenet.keys.KeyBlock;
import freenet.keys.NodeCHK;
import freenet.keys.SSKBlock;
import freenet.keys.SSKVerifyException;
import freenet.node.Node.NodeInitException;
import freenet.node.fcp.FCPServer;
import freenet.node.useralerts.UserAlertManager;
import freenet.store.KeyCollisionException;
import freenet.support.Base64;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.BooleanCallback;
import freenet.support.api.BucketFactory;
import freenet.support.api.StringCallback;
import freenet.support.io.FilenameGenerator;
import freenet.support.io.PaddedEphemerallyEncryptedBucketFactory;
import freenet.support.io.PersistentEncryptedTempBucketFactory;
import freenet.support.io.PersistentTempBucketFactory;
import freenet.support.io.TempBucketFactory;

/**
 * The connection between the node and the client layer.
 */
public class NodeClientCore {

	private static boolean logMINOR;
	public final USKManager uskManager;
	final ArchiveManager archiveManager;
	public final RequestStarterGroup requestStarters;
	private final HealingQueue healingQueue;
	/** Must be included as a hidden field in order for any dangerous HTTP operation to complete successfully. */
	public final String formPassword;

	File downloadDir;
	final FilenameGenerator tempFilenameGenerator;
	public final BucketFactory tempBucketFactory;
	final Node node;
	public final RandomSource random;
	final File tempDir;

	// Persistent temporary buckets
	public final PersistentTempBucketFactory persistentTempBucketFactory;
	public final PersistentEncryptedTempBucketFactory persistentEncryptedTempBucketFactory;
	
	public final UserAlertManager alerts;
	TextModeClientInterfaceServer tmci;
	TextModeClientInterface directTMCI;
	FCPServer fcpServer;
	FProxyToadlet fproxyServlet;
	SimpleToadletServer toadletContainer;
	// FIXME why isn't this just in fproxy?
	public BookmarkManager bookmarkManager;
	public final BackgroundBlockEncoder backgroundBlockEncoder;
	/** If true, allow extra path components at the end of URIs */
	public boolean ignoreTooManyPathComponents;
	/** If true, requests are resumed lazily i.e. startup does not block waiting for them. */
	private boolean lazyResume;
	
	// Client stuff that needs to be configged - FIXME
	static final int MAX_ARCHIVE_HANDLERS = 200; // don't take up much RAM... FIXME
	static final long MAX_CACHED_ARCHIVE_DATA = 32*1024*1024; // make a fixed fraction of the store by default? FIXME
	static final long MAX_ARCHIVE_SIZE = 2*1024*1024; // ??? FIXME
	static final long MAX_ARCHIVED_FILE_SIZE = 1024*1024; // arbitrary... FIXME
	static final int MAX_CACHED_ELEMENTS = 1024; // equally arbitrary! FIXME hopefully we can cache many of these though

	NodeClientCore(Node node, Config config, SubConfig nodeConfig, File nodeDir, int portNumber, int sortOrder, SimpleFieldSet throttleFS) throws NodeInitException {
		this.node = node;
		this.random = node.random;
		this.backgroundBlockEncoder = new BackgroundBlockEncoder();
		Thread t = new Thread(backgroundBlockEncoder, "Background block encoder");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		t.start();
	  	byte[] pwdBuf = new byte[16];
		random.nextBytes(pwdBuf);
		this.formPassword = Base64.encode(pwdBuf);
		alerts = new UserAlertManager(this);
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "Serializing RequestStarterGroup from:\n"+throttleFS);
		requestStarters = new RequestStarterGroup(node, this, portNumber, random, config, throttleFS);
		
		// Temp files
		
		nodeConfig.register("tempDir", new File(nodeDir, "temp-"+portNumber).toString(), sortOrder++, true, false, "Temp files directory", "Name of directory to put temporary files in", 
				new StringCallback() {
					public String get() {
						return tempDir.getPath();
					}
					public void set(String val) throws InvalidConfigValueException {
						if(tempDir.equals(new File(val))) return;
						// FIXME
						throw new InvalidConfigValueException("Moving temp directory on the fly not supported at present");
					}
		});
		
		tempDir = new File(nodeConfig.getString("tempDir"));
		if(!((tempDir.exists() && tempDir.isDirectory()) || (tempDir.mkdir()))) {
			String msg = "Could not find or create temporary directory";
			throw new NodeInitException(Node.EXIT_BAD_TEMP_DIR, msg);
		}
		
		try {
			tempFilenameGenerator = new FilenameGenerator(random, true, tempDir, "temp-");
		} catch (IOException e) {
			String msg = "Could not find or create temporary directory (filename generator)";
			throw new NodeInitException(Node.EXIT_BAD_TEMP_DIR, msg);
		}

		// Persistent temp files
		nodeConfig.register("persistentTempDir", new File(nodeDir, "persistent-temp-"+portNumber).toString(), sortOrder++, true, false, "Persistent temp files directory", "Name of directory to put persistent temp files in",
				new StringCallback() {
					public String get() {
						return persistentTempBucketFactory.getDir().toString();
					}
					public void set(String val) throws InvalidConfigValueException {
						if(!get().equals(val))
							return;
						// FIXME
						throw new InvalidConfigValueException("Moving persistent temp directory on the fly not supported at present");
					}
		});
		try {
			persistentTempBucketFactory = new PersistentTempBucketFactory(new File(nodeConfig.getString("persistentTempDir")), "freenet-temp-", random);
			persistentEncryptedTempBucketFactory = new PersistentEncryptedTempBucketFactory(persistentTempBucketFactory);
		} catch (IOException e2) {
			String msg = "Could not find or create persistent temporary directory";
			throw new NodeInitException(Node.EXIT_BAD_TEMP_DIR, msg);
		}

		tempBucketFactory = new PaddedEphemerallyEncryptedBucketFactory(new TempBucketFactory(tempFilenameGenerator), random, 1024);
		
		// Downloads directory
		
		nodeConfig.register("downloadsDir", "downloads", sortOrder++, true, true, "Default download directory", "The directory to save downloaded files into by default", new StringCallback() {

			public String get() {
				return downloadDir.getPath();
			}

			public void set(String val) throws InvalidConfigValueException {
				if(downloadDir.equals(new File(val)))
					return;
				File f = new File(val);
				if(!((f.exists() && f.isDirectory()) || (f.mkdir()))) {
					throw new InvalidConfigValueException("Could not find or create directory");
				}
				downloadDir = new File(val);
			}
			
		});
		
		String val = nodeConfig.getString("downloadsDir");
		downloadDir = new File(val);
		if(!((downloadDir.exists() && downloadDir.isDirectory()) || (downloadDir.mkdir()))) {
			throw new NodeInitException(Node.EXIT_BAD_DOWNLOADS_DIR, "Could not find or create default downloads directory");
		}


		archiveManager = new ArchiveManager(MAX_ARCHIVE_HANDLERS, MAX_CACHED_ARCHIVE_DATA, MAX_ARCHIVE_SIZE, MAX_ARCHIVED_FILE_SIZE, MAX_CACHED_ELEMENTS, random, tempFilenameGenerator);
		Logger.normal(this, "Initializing USK Manager");
		System.out.println("Initializing USK Manager");
		uskManager = new USKManager(this);
		
		healingQueue = new SimpleHealingQueue(requestStarters.chkPutScheduler,
				new InserterContext(tempBucketFactory, tempBucketFactory, persistentTempBucketFactory, 
						random, 0, 2, 1, 0, 0, new SimpleEventProducer(), 
						!Node.DONT_CACHE_LOCAL_REQUESTS, uskManager, backgroundBlockEncoder), RequestStarter.PREFETCH_PRIORITY_CLASS, 512 /* FIXME make configurable */);
		
		nodeConfig.register("ignoreTooManyPathComponents", true, sortOrder++, true, false, "Ignore too many path components", 
				"If true, the node won't generate TOO_MANY_PATH_COMPONENTS errors when a URI is fed to it which has extra, meaningless subdirs (/blah/blah) on the end beyond what is needed to fetch the key (for example, old CHKs will often have filenames stuck on the end which weren't part of the original insert; this is obsolete because we can now include the filename, and it is confusing to be able to add arbitrary strings to a URI, and it makes them hard to compare). Only enable this option if you need it for compatibility with older apps; it will be removed soon.", new BooleanCallback() {

					public boolean get() {
						return ignoreTooManyPathComponents;
					}

					public void set(boolean val) throws InvalidConfigValueException {
						synchronized(NodeClientCore.this) {
							ignoreTooManyPathComponents = val;
						}
					}
			
		});
		
		ignoreTooManyPathComponents = nodeConfig.getBoolean("ignoreTooManyPathComponents");
		
		nodeConfig.register("lazyResume", false, sortOrder++, true, false, "Complete loading of persistent requests after startup? (Uses more memory)",
				"The node can load persistent queued requests during startup, or it can read the data into memory and then complete the request resuming process after the node has started up. "+
				"Shorter start-up times, but uses more memory.", new BooleanCallback() {

					public boolean get() {
						return lazyResume;
					}

					public void set(boolean val) throws InvalidConfigValueException {
						synchronized(NodeClientCore.this) {
							lazyResume = val;
						}
					}
					
		});
		
		lazyResume = nodeConfig.getBoolean("lazyResume");
		
	}

	public void start(Config config) throws NodeInitException {
		
		// TMCI
		try{
			TextModeClientInterfaceServer.maybeCreate(node, config);
		} catch (IOException e) {
			e.printStackTrace();
			throw new NodeInitException(Node.EXIT_COULD_NOT_START_TMCI, "Could not start TMCI: "+e);
		}
		
		// FCP (including persistent requests so needs to start before FProxy)
		try {
			fcpServer = FCPServer.maybeCreate(node, this, node.config);
		} catch (IOException e) {
			throw new NodeInitException(Node.EXIT_COULD_NOT_START_FCP, "Could not start FCP: "+e);
		} catch (InvalidConfigValueException e) {
			throw new NodeInitException(Node.EXIT_COULD_NOT_START_FCP, "Could not start FCP: "+e);
		}
		
		SubConfig fproxyConfig = new SubConfig("fproxy", config);
		bookmarkManager = new BookmarkManager(this, fproxyConfig);
		
		// FProxy
		// FIXME this is a hack, the real way to do this is plugins
		try {
			FProxyToadlet.maybeCreateFProxyEtc(this, node, config, fproxyConfig);
		} catch (IOException e) {
			e.printStackTrace();
			throw new NodeInitException(Node.EXIT_COULD_NOT_START_FPROXY, "Could not start FProxy: "+e);
		} catch (InvalidConfigValueException e) {
			throw new NodeInitException(Node.EXIT_COULD_NOT_START_FPROXY, "Could not start FProxy: "+e);
		}

		Thread completer = new Thread(new Runnable() {
			public void run() {
				System.out.println("Resuming persistent requests");
				Logger.normal(this, "Resuming persistent requests");
				// Call it anyway; if we are not lazy, it won't have to start any requests
				// But it does other things too
				fcpServer.finishStart();
				persistentTempBucketFactory.completedInit();
				System.out.println("Completed startup: All persistent requests resumed or restarted");
				Logger.normal(this, "Completed startup: All persistent requests resumed or restarted");
			}
		}, "Startup completion thread");
		completer.setDaemon(true);
		completer.start();
	}
	
	public ClientKeyBlock realGetKey(ClientKey key, boolean localOnly, boolean cache, boolean ignoreStore) throws LowLevelGetException {
		if(key instanceof ClientCHK)
			return realGetCHK((ClientCHK)key, localOnly, cache, ignoreStore);
		else if(key instanceof ClientSSK)
			return realGetSSK((ClientSSK)key, localOnly, cache, ignoreStore);
		else
			throw new IllegalArgumentException("Not a CHK or SSK: "+key);
	}
	
	ClientCHKBlock realGetCHK(ClientCHK key, boolean localOnly, boolean cache, boolean ignoreStore) throws LowLevelGetException {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		long startTime = System.currentTimeMillis();
		long uid = random.nextLong();
		if(!node.lockUID(uid)) {
			Logger.error(this, "Could not lock UID just randomly generated: "+uid+" - probably indicates broken PRNG");
			throw new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR);
		}
		Object o = node.makeRequestSender(key.getNodeCHK(), node.maxHTL(), uid, null, node.getLocation(), false, localOnly, cache, ignoreStore);
		if(o instanceof CHKBlock) {
			try {
				return new ClientCHKBlock((CHKBlock)o, key);
			} catch (CHKVerifyException e) {
				Logger.error(this, "Does not verify: "+e, e);
				throw new LowLevelGetException(LowLevelGetException.DECODE_FAILED);
			}
		}
		if(o == null) {
			throw new LowLevelGetException(LowLevelGetException.DATA_NOT_FOUND_IN_STORE);
		}
		RequestSender rs = (RequestSender)o;
		boolean rejectedOverload = false;
		short waitStatus = 0;
		while(true) {
			waitStatus = rs.waitUntilStatusChange(waitStatus);
			if((!rejectedOverload) && (waitStatus & RequestSender.WAIT_REJECTED_OVERLOAD) != 0) {
				// See below; inserts count both
				requestStarters.throttleWindow.rejectedOverload();
				rejectedOverload = true;
			}

			int status = rs.getStatus();
			
			if(status == RequestSender.NOT_FINISHED) 
				continue;
			
	        if(status != RequestSender.TIMED_OUT && status != RequestSender.GENERATED_REJECTED_OVERLOAD && status != RequestSender.INTERNAL_ERROR) {
	        	if(logMINOR) Logger.minor(this, "CHK fetch cost "+rs.getTotalSentBytes()+ '/' +rs.getTotalReceivedBytes()+" bytes ("+status+ ')');
            	node.localChkFetchBytesSentAverage.report(rs.getTotalSentBytes());
            	node.localChkFetchBytesReceivedAverage.report(rs.getTotalReceivedBytes());
	        }
			
			if((status == RequestSender.TIMED_OUT) ||
					(status == RequestSender.GENERATED_REJECTED_OVERLOAD)) {
				if(!rejectedOverload) {
					// See below
					requestStarters.throttleWindow.rejectedOverload();
					rejectedOverload = true;
				}
			} else {
				if(rs.hasForwarded() &&
						((status == RequestSender.DATA_NOT_FOUND) ||
						(status == RequestSender.SUCCESS) ||
						(status == RequestSender.ROUTE_NOT_FOUND) ||
						(status == RequestSender.VERIFY_FAILURE))) {
					long rtt = System.currentTimeMillis() - startTime;
					if(!rejectedOverload)
						requestStarters.throttleWindow.requestCompleted();
					requestStarters.chkRequestThrottle.successfulCompletion(rtt);
				}
			}
			
			if(rs.getStatus() == RequestSender.SUCCESS) {
				try {
					return new ClientCHKBlock(rs.getPRB().getBlock(), rs.getHeaders(), key, true);
				} catch (CHKVerifyException e) {
					Logger.error(this, "Does not verify: "+e, e);
					throw new LowLevelGetException(LowLevelGetException.DECODE_FAILED);
				} catch (AbortedException e) {
					Logger.error(this, "Impossible: "+e, e);
					throw new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR);
				}
			} else {
				switch(rs.getStatus()) {
				case RequestSender.NOT_FINISHED:
					Logger.error(this, "RS still running in getCHK!: "+rs);
					throw new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR);
				case RequestSender.DATA_NOT_FOUND:
					throw new LowLevelGetException(LowLevelGetException.DATA_NOT_FOUND);
				case RequestSender.ROUTE_NOT_FOUND:
					throw new LowLevelGetException(LowLevelGetException.ROUTE_NOT_FOUND);
				case RequestSender.TRANSFER_FAILED:
					throw new LowLevelGetException(LowLevelGetException.TRANSFER_FAILED);
				case RequestSender.VERIFY_FAILURE:
					throw new LowLevelGetException(LowLevelGetException.VERIFY_FAILED);
				case RequestSender.GENERATED_REJECTED_OVERLOAD:
				case RequestSender.TIMED_OUT:
					throw new LowLevelGetException(LowLevelGetException.REJECTED_OVERLOAD);
				case RequestSender.INTERNAL_ERROR:
					throw new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR);
				default:
					Logger.error(this, "Unknown RequestSender code in getCHK: "+rs.getStatus()+" on "+rs);
					throw new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR);
				}
			}
		}
	}

	ClientSSKBlock realGetSSK(ClientSSK key, boolean localOnly, boolean cache, boolean ignoreStore) throws LowLevelGetException {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		long startTime = System.currentTimeMillis();
		long uid = random.nextLong();
		if(!node.lockUID(uid)) {
			Logger.error(this, "Could not lock UID just randomly generated: "+uid+" - probably indicates broken PRNG");
			throw new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR);
		}
		Object o = node.makeRequestSender(key.getNodeKey(), node.maxHTL(), uid, null, node.getLocation(), false, localOnly, cache, ignoreStore);
		if(o instanceof SSKBlock) {
			try {
				SSKBlock block = (SSKBlock)o;
				key.setPublicKey(block.getPubKey());
				return new ClientSSKBlock(block, key);
			} catch (SSKVerifyException e) {
				Logger.error(this, "Does not verify: "+e, e);
				throw new LowLevelGetException(LowLevelGetException.DECODE_FAILED);
			}
		}
		if(o == null) {
			throw new LowLevelGetException(LowLevelGetException.DATA_NOT_FOUND_IN_STORE);
		}
		RequestSender rs = (RequestSender)o;
		boolean rejectedOverload = false;
		short waitStatus = 0;
		while(true) {
			waitStatus = rs.waitUntilStatusChange(waitStatus);
			if((!rejectedOverload) && (waitStatus & RequestSender.WAIT_REJECTED_OVERLOAD) != 0) {
				requestStarters.throttleWindow.rejectedOverload();
				rejectedOverload = true;
			}

			int status = rs.getStatus();
			
			if(status == RequestSender.NOT_FINISHED) 
				continue;

	        if(status != RequestSender.TIMED_OUT && status != RequestSender.GENERATED_REJECTED_OVERLOAD && status != RequestSender.INTERNAL_ERROR) {
            	if(logMINOR) Logger.minor(this, "SSK fetch cost "+rs.getTotalSentBytes()+ '/' +rs.getTotalReceivedBytes()+" bytes ("+status+ ')');
            	node.localSskFetchBytesSentAverage.report(rs.getTotalSentBytes());
            	node.localSskFetchBytesReceivedAverage.report(rs.getTotalReceivedBytes());
	        }
			
			if((status == RequestSender.TIMED_OUT) ||
					(status == RequestSender.GENERATED_REJECTED_OVERLOAD)) {
				if(!rejectedOverload) {
					requestStarters.throttleWindow.rejectedOverload();
					rejectedOverload = true;
				}
			} else {
				if(rs.hasForwarded() &&
						((status == RequestSender.DATA_NOT_FOUND) ||
						(status == RequestSender.SUCCESS) ||
						(status == RequestSender.ROUTE_NOT_FOUND) ||
						(status == RequestSender.VERIFY_FAILURE))) {
					long rtt = System.currentTimeMillis() - startTime;
					
					if(!rejectedOverload)
						requestStarters.throttleWindow.requestCompleted();
					requestStarters.sskRequestThrottle.successfulCompletion(rtt);
				}
			}
			
			if(rs.getStatus() == RequestSender.SUCCESS) {
				try {
					SSKBlock block = rs.getSSKBlock();
					key.setPublicKey(block.getPubKey());
					return new ClientSSKBlock(block, key);
				} catch (SSKVerifyException e) {
					Logger.error(this, "Does not verify: "+e, e);
					throw new LowLevelGetException(LowLevelGetException.DECODE_FAILED);
				}
			} else {
				switch(rs.getStatus()) {
				case RequestSender.NOT_FINISHED:
					Logger.error(this, "RS still running in getCHK!: "+rs);
					throw new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR);
				case RequestSender.DATA_NOT_FOUND:
					throw new LowLevelGetException(LowLevelGetException.DATA_NOT_FOUND);
				case RequestSender.ROUTE_NOT_FOUND:
					throw new LowLevelGetException(LowLevelGetException.ROUTE_NOT_FOUND);
				case RequestSender.TRANSFER_FAILED:
					Logger.error(this, "WTF? Transfer failed on an SSK? on "+uid);
					throw new LowLevelGetException(LowLevelGetException.TRANSFER_FAILED);
				case RequestSender.VERIFY_FAILURE:
					throw new LowLevelGetException(LowLevelGetException.VERIFY_FAILED);
				case RequestSender.GENERATED_REJECTED_OVERLOAD:
				case RequestSender.TIMED_OUT:
					throw new LowLevelGetException(LowLevelGetException.REJECTED_OVERLOAD);
				case RequestSender.INTERNAL_ERROR:
				default:
					Logger.error(this, "Unknown RequestSender code in getCHK: "+rs.getStatus()+" on "+rs);
					throw new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR);
				}
			}
		}
	}

	public void realPut(KeyBlock block, boolean cache) throws LowLevelPutException {
		if(block instanceof CHKBlock)
			realPutCHK((CHKBlock)block, cache);
		else if(block instanceof SSKBlock)
			realPutSSK((SSKBlock)block, cache);
		else
			throw new IllegalArgumentException("Unknown put type "+block.getClass());
	}
	
	public void realPutCHK(CHKBlock block, boolean cache) throws LowLevelPutException {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		byte[] data = block.getData();
		byte[] headers = block.getHeaders();
		PartiallyReceivedBlock prb = new PartiallyReceivedBlock(Node.PACKETS_IN_BLOCK, Node.PACKET_SIZE, data);
		CHKInsertSender is;
		long uid = random.nextLong();
		if(!node.lockUID(uid)) {
			Logger.error(this, "Could not lock UID just randomly generated: "+uid+" - probably indicates broken PRNG");
			throw new LowLevelPutException(LowLevelPutException.INTERNAL_ERROR);
		}
		long startTime = System.currentTimeMillis();
		if(cache) {
			node.store(block);
		}
		is = node.makeInsertSender((NodeCHK)block.getKey(), 
				node.maxHTL(), uid, null, headers, prb, false, node.getLocation(), cache);
		boolean hasReceivedRejectedOverload = false;
		// Wait for status
		while(true) {
			synchronized(is) {
				if(is.getStatus() == CHKInsertSender.NOT_FINISHED) {
					try {
						is.wait(5*1000);
					} catch (InterruptedException e) {
						// Ignore
					}
				}
				if(is.getStatus() != CHKInsertSender.NOT_FINISHED) break;
			}
			if((!hasReceivedRejectedOverload) && is.receivedRejectedOverload()) {
				hasReceivedRejectedOverload = true;
				requestStarters.throttleWindow.rejectedOverload();
			}
		}
		
		// Wait for completion
		while(true) {
			synchronized(is) {
				if(is.completed()) break;
				try {
					is.wait(10*1000);
				} catch (InterruptedException e) {
					// Go around again
				}
			}
			if(is.anyTransfersFailed() && (!hasReceivedRejectedOverload)) {
				hasReceivedRejectedOverload = true; // not strictly true but same effect
				requestStarters.throttleWindow.rejectedOverload();
			}
		}
		
		if(logMINOR) Logger.minor(this, "Completed "+uid+" overload="+hasReceivedRejectedOverload+ ' ' +is.getStatusString());
		
		// Finished?
		if(!hasReceivedRejectedOverload) {
			// Is it ours? Did we send a request?
			if(is.sentRequest() && (is.uid == uid) && ((is.getStatus() == CHKInsertSender.ROUTE_NOT_FOUND) 
					|| (is.getStatus() == CHKInsertSender.SUCCESS))) {
				// It worked!
				long endTime = System.currentTimeMillis();
				long len = endTime - startTime;
				
				requestStarters.chkInsertThrottle.successfulCompletion(len);
				if(!hasReceivedRejectedOverload)
					requestStarters.throttleWindow.requestCompleted();
			}
		}
		
		int status = is.getStatus();
        if(status != CHKInsertSender.TIMED_OUT && status != CHKInsertSender.GENERATED_REJECTED_OVERLOAD && status != CHKInsertSender.INTERNAL_ERROR
        		&& status != CHKInsertSender.ROUTE_REALLY_NOT_FOUND) {
        	int sent = is.getTotalSentBytes();
        	int received = is.getTotalReceivedBytes();
        	if(logMINOR) Logger.minor(this, "Local CHK insert cost "+sent+ '/' +received+" bytes ("+status+ ')');
        	node.localChkInsertBytesSentAverage.report(sent);
        	node.localChkInsertBytesReceivedAverage.report(received);
        }
        
		if(status == CHKInsertSender.SUCCESS) {
			Logger.normal(this, "Succeeded inserting "+block);
			return;
		} else {
			String msg = "Failed inserting "+block+" : "+is.getStatusString();
			if(status == CHKInsertSender.ROUTE_NOT_FOUND)
				msg += " - this is normal on small networks; the data will still be propagated, but it can't find the 20+ nodes needed for full success";
			if(is.getStatus() != CHKInsertSender.ROUTE_NOT_FOUND)
				Logger.error(this, msg);
			else
				Logger.normal(this, msg);
			switch(is.getStatus()) {
			case CHKInsertSender.NOT_FINISHED:
				Logger.error(this, "IS still running in putCHK!: "+is);
				throw new LowLevelPutException(LowLevelPutException.INTERNAL_ERROR);
			case CHKInsertSender.GENERATED_REJECTED_OVERLOAD:
			case CHKInsertSender.TIMED_OUT:
				throw new LowLevelPutException(LowLevelPutException.REJECTED_OVERLOAD);
			case CHKInsertSender.ROUTE_NOT_FOUND:
				throw new LowLevelPutException(LowLevelPutException.ROUTE_NOT_FOUND);
			case CHKInsertSender.ROUTE_REALLY_NOT_FOUND:
				throw new LowLevelPutException(LowLevelPutException.ROUTE_REALLY_NOT_FOUND);
			case CHKInsertSender.INTERNAL_ERROR:
				throw new LowLevelPutException(LowLevelPutException.INTERNAL_ERROR);
			default:
				Logger.error(this, "Unknown CHKInsertSender code in putCHK: "+is.getStatus()+" on "+is);
				throw new LowLevelPutException(LowLevelPutException.INTERNAL_ERROR);
			}
		}
	}

	public void realPutSSK(SSKBlock block, boolean cache) throws LowLevelPutException {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		SSKInsertSender is;
		long uid = random.nextLong();
		if(!node.lockUID(uid)) {
			Logger.error(this, "Could not lock UID just randomly generated: "+uid+" - probably indicates broken PRNG");
			throw new LowLevelPutException(LowLevelPutException.INTERNAL_ERROR);
		}
		long startTime = System.currentTimeMillis();
		if(cache) {
			try {
				if(cache)
					node.storeInsert(block);
			} catch (KeyCollisionException e) {
				throw new LowLevelPutException(LowLevelPutException.COLLISION);
			}
		}
		is = node.makeInsertSender(block, 
				node.maxHTL(), uid, null, false, node.getLocation(), false, cache);
		boolean hasReceivedRejectedOverload = false;
		// Wait for status
		while(true) {
			synchronized(is) {
				if(is.getStatus() == SSKInsertSender.NOT_FINISHED) {
					try {
						is.wait(5*1000);
					} catch (InterruptedException e) {
						// Ignore
					}
				}
				if(is.getStatus() != SSKInsertSender.NOT_FINISHED) break;
			}
			if((!hasReceivedRejectedOverload) && is.receivedRejectedOverload()) {
				hasReceivedRejectedOverload = true;
				requestStarters.throttleWindow.rejectedOverload();
			}
		}
		
		// Wait for completion
		while(true) {
			synchronized(is) {
				if(is.getStatus() != SSKInsertSender.NOT_FINISHED) break;
				try {
					is.wait(10*1000);
				} catch (InterruptedException e) {
					// Go around again
				}
			}
		}
		
		if(logMINOR) Logger.minor(this, "Completed "+uid+" overload="+hasReceivedRejectedOverload+ ' ' +is.getStatusString());
		
		// Finished?
		if(!hasReceivedRejectedOverload) {
			// Is it ours? Did we send a request?
			if(is.sentRequest() && (is.uid == uid) && ((is.getStatus() == SSKInsertSender.ROUTE_NOT_FOUND) 
					|| (is.getStatus() == SSKInsertSender.SUCCESS))) {
				// It worked!
				long endTime = System.currentTimeMillis();
				long rtt = endTime - startTime;
				requestStarters.throttleWindow.requestCompleted();
				requestStarters.sskInsertThrottle.successfulCompletion(rtt);
			}
		}

		int status = is.getStatus();
		
        if(status != CHKInsertSender.TIMED_OUT && status != CHKInsertSender.GENERATED_REJECTED_OVERLOAD && status != CHKInsertSender.INTERNAL_ERROR
        		&& status != CHKInsertSender.ROUTE_REALLY_NOT_FOUND) {
        	int sent = is.getTotalSentBytes();
        	int received = is.getTotalReceivedBytes();
        	if(logMINOR) Logger.minor(this, "Local SSK insert cost "+sent+ '/' +received+" bytes ("+status+ ')');
        	node.localSskInsertBytesSentAverage.report(sent);
        	node.localSskInsertBytesReceivedAverage.report(received);
        }
        
		if(is.hasCollided()) {
			// Store it locally so it can be fetched immediately, and overwrites any locally inserted.
			try {
				node.storeInsert(is.getBlock());
			} catch (KeyCollisionException e) {
				// Impossible
			}
			throw new LowLevelPutException(LowLevelPutException.COLLISION);
		}
		
		if(status == SSKInsertSender.SUCCESS) {
			Logger.normal(this, "Succeeded inserting "+block);
			return;
		} else {
			String msg = "Failed inserting "+block+" : "+is.getStatusString();
			if(status == CHKInsertSender.ROUTE_NOT_FOUND)
				msg += " - this is normal on small networks; the data will still be propagated, but it can't find the 20+ nodes needed for full success";
			if(is.getStatus() != SSKInsertSender.ROUTE_NOT_FOUND)
				Logger.error(this, msg);
			else
				Logger.normal(this, msg);
			switch(is.getStatus()) {
			case SSKInsertSender.NOT_FINISHED:
				Logger.error(this, "IS still running in putCHK!: "+is);
				throw new LowLevelPutException(LowLevelPutException.INTERNAL_ERROR);
			case SSKInsertSender.GENERATED_REJECTED_OVERLOAD:
			case SSKInsertSender.TIMED_OUT:
				throw new LowLevelPutException(LowLevelPutException.REJECTED_OVERLOAD);
			case SSKInsertSender.ROUTE_NOT_FOUND:
				throw new LowLevelPutException(LowLevelPutException.ROUTE_NOT_FOUND);
			case SSKInsertSender.ROUTE_REALLY_NOT_FOUND:
				throw new LowLevelPutException(LowLevelPutException.ROUTE_REALLY_NOT_FOUND);
			case SSKInsertSender.INTERNAL_ERROR:
				throw new LowLevelPutException(LowLevelPutException.INTERNAL_ERROR);
			default:
				Logger.error(this, "Unknown CHKInsertSender code in putSSK: "+is.getStatus()+" on "+is);
				throw new LowLevelPutException(LowLevelPutException.INTERNAL_ERROR);
			}
		}
	}

	public HighLevelSimpleClient makeClient(short prioClass) {
		return makeClient(prioClass, false);
	}
	
	public HighLevelSimpleClient makeClient(short prioClass, boolean forceDontIgnoreTooManyPathComponents) {
		return new HighLevelSimpleClientImpl(this, archiveManager, tempBucketFactory, random, !Node.DONT_CACHE_LOCAL_REQUESTS, prioClass, forceDontIgnoreTooManyPathComponents);
	}
	
	public FCPServer getFCPServer() {
		return fcpServer;
	}

	public void setToadletContainer(SimpleToadletServer server) {
		toadletContainer = server;
	}

	public FProxyToadlet getFProxy() {
		return fproxyServlet;
	}

	public SimpleToadletServer getToadletContainer() {
		return toadletContainer;
	}
	
	public TextModeClientInterfaceServer getTextModeClientInterface(){
		return tmci;
	}

	public void setFProxy(FProxyToadlet fproxy) {
		this.fproxyServlet = fproxy;
	}

	public void setFCPServer(FCPServer fcp) {
		this.fcpServer = fcp;
	}
	
	public void setTMCI(TextModeClientInterfaceServer server) {
		this.tmci = server;
	}

	public TextModeClientInterface getDirectTMCI() {
		return directTMCI;
	}
	
	public void setDirectTMCI(TextModeClientInterface i) {
		this.directTMCI = i;
	}

	public File getDownloadDir() {
		return downloadDir;
	}

	public HealingQueue getHealingQueue() {
		return healingQueue;
	}

	public void queueRandomReinsert(KeyBlock block) {
		SimpleSendableInsert ssi = new SimpleSendableInsert(this, block, RequestStarter.MAXIMUM_PRIORITY_CLASS);
		if(logMINOR) Logger.minor(this, "Queueing random reinsert for "+block+" : "+ssi);
		if(block instanceof CHKBlock)
			requestStarters.chkPutScheduler.register(ssi);
		else if(block instanceof SSKBlock)
			requestStarters.sskPutScheduler.register(ssi);
		else
			Logger.error(this, "Don't know what to do with "+block+" should be queued for reinsert");
	}

	public void storeConfig() {
		Logger.normal(this, "Trying to write config to disk", new Exception("debug"));
		node.config.store();
	}
	
	public boolean isTestnetEnabled() {
		return node.isTestnetEnabled();
	}

	public boolean isAdvancedModeEnabled() {
		return (getToadletContainer() != null) &&
			getToadletContainer().isAdvancedModeEnabled();
	}

	public boolean isFProxyJavascriptEnabled() {
		return (getToadletContainer() != null) &&
			getToadletContainer().isFProxyJavascriptEnabled();
	}

	public String getMyName() {
		return node.getMyName();
	}

	public FilterCallback createFilterCallback(URI uri, FoundURICallback cb) {
		if(logMINOR) Logger.minor(this, "Creating filter callback: "+uri+", "+cb);
		return new GenericReadFilterCallback(uri, cb);
	}
	
	public boolean lazyResume() {
		return lazyResume;
	}
}
