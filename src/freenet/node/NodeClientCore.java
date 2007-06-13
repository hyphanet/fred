package freenet.node;

import java.io.File;
import java.io.IOException;
import java.net.URI;

import freenet.client.ArchiveManager;
import freenet.client.HighLevelSimpleClient;
import freenet.client.HighLevelSimpleClientImpl;
import freenet.client.InsertContext;
import freenet.client.async.BackgroundBlockEncoder;
import freenet.client.async.HealingQueue;
import freenet.client.async.SimpleHealingQueue;
import freenet.client.async.USKManager;
import freenet.client.events.SimpleEventProducer;
import freenet.clients.http.bookmark.BookmarkManager;
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
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.NodeCHK;
import freenet.keys.SSKBlock;
import freenet.keys.SSKVerifyException;
import freenet.l10n.L10n;
import freenet.node.Node.NodeInitException;
import freenet.node.fcp.FCPServer;
import freenet.node.useralerts.UserAlertManager;
import freenet.store.KeyCollisionException;
import freenet.support.Base64;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.BooleanCallback;
import freenet.support.api.IntCallback;
import freenet.support.api.BucketFactory;
import freenet.support.api.StringArrCallback;
import freenet.support.api.StringCallback;
import freenet.support.io.FileUtil;
import freenet.support.io.FilenameGenerator;
import freenet.support.io.PaddedEphemerallyEncryptedBucketFactory;
import freenet.support.io.PersistentEncryptedTempBucketFactory;
import freenet.support.io.PersistentTempBucketFactory;
import freenet.support.io.TempBucketFactory;

/**
 * The connection between the node and the client layer.
 */
public class NodeClientCore implements Persistable {

	private static boolean logMINOR;
	public final USKManager uskManager;
	final ArchiveManager archiveManager;
	public final RequestStarterGroup requestStarters;
	private final HealingQueue healingQueue;
	/** Must be included as a hidden field in order for any dangerous HTTP operation to complete successfully. */
	public final String formPassword;

	File downloadDir;
	private File[] downloadAllowedDirs;
	private boolean includeDownloadDir;
	private boolean downloadAllowedEverywhere;
	private File[] uploadAllowedDirs;
	private boolean uploadAllowedEverywhere;
	final FilenameGenerator tempFilenameGenerator;
	public final BucketFactory tempBucketFactory;
	final Node node;
	final NodeStats nodeStats;
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
	protected final Persister persister;
	
	public static int maxBackgroundUSKFetchers;
	
	// Client stuff that needs to be configged - FIXME
	static final int MAX_ARCHIVE_HANDLERS = 200; // don't take up much RAM... FIXME
	static final long MAX_CACHED_ARCHIVE_DATA = 32*1024*1024; // make a fixed fraction of the store by default? FIXME
	static final long MAX_ARCHIVE_SIZE = 2*1024*1024; // ??? FIXME
	static final long MAX_ARCHIVED_FILE_SIZE = 1024*1024; // arbitrary... FIXME
	static final int MAX_CACHED_ELEMENTS = 1024; // equally arbitrary! FIXME hopefully we can cache many of these though

	NodeClientCore(Node node, Config config, SubConfig nodeConfig, File nodeDir, int portNumber, int sortOrder, SimpleFieldSet oldThrottleFS) throws NodeInitException {
		this.node = node;
		this.nodeStats = node.nodeStats;
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
		
		persister = new ConfigurablePersister(this, nodeConfig, "clientThrottleFile", "client-throttle.dat", sortOrder++, true, false, 
				"NodeClientCore.fileForClientStats", "NodeClientCore.fileForClientStatsLong", node.ps);
		
		SimpleFieldSet throttleFS = persister.read();
		
		if(throttleFS == null)
			throttleFS = oldThrottleFS;

		if(logMINOR) Logger.minor(this, "Read throttleFS:\n"+throttleFS);
		
		if(logMINOR) Logger.minor(this, "Serializing RequestStarterGroup from:\n"+throttleFS);
		requestStarters = new RequestStarterGroup(node, this, portNumber, random, config, throttleFS);
		
		// Temp files
		
		nodeConfig.register("tempDir", new File(nodeDir, "temp-"+portNumber).toString(), sortOrder++, true, false, "NodeClientCore.tempDir", "NodeClientCore.tempDirLong", 
				new StringCallback() {
					public String get() {
						return tempDir.getPath();
					}
					public void set(String val) throws InvalidConfigValueException {
						if(tempDir.equals(new File(val))) return;
						// FIXME
						throw new InvalidConfigValueException(l10n("movingTempDirOnTheFlyNotSupported"));
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
		nodeConfig.register("persistentTempDir", new File(nodeDir, "persistent-temp-"+portNumber).toString(), sortOrder++, true, false, "NodeClientCore.persistentTempDir", "NodeClientCore.persistentTempDirLong",
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
		
		nodeConfig.register("downloadsDir", "downloads", sortOrder++, true, true, "NodeClientCore.downloadDir", "NodeClientCore.downloadDirLong", new StringCallback() {

			public String get() {
				return downloadDir.getPath();
			}

			public void set(String val) throws InvalidConfigValueException {
				if(downloadDir.equals(new File(val)))
					return;
				File f = new File(val);
				if(!((f.exists() && f.isDirectory()) || (f.mkdir()))) {
					// Relatively commonly used, despite being advanced (i.e. not something we want to show to newbies). So translate it.
					throw new InvalidConfigValueException(l10n("couldNotFindOrCreateDir"));
				}
				downloadDir = new File(val);
			}
			
		});
		
		String val = nodeConfig.getString("downloadsDir");
		downloadDir = new File(val);
		if(!((downloadDir.exists() && downloadDir.isDirectory()) || (downloadDir.mkdir()))) {
			throw new NodeInitException(Node.EXIT_BAD_DOWNLOADS_DIR, "Could not find or create default downloads directory");
		}

		// Downloads allowed, uploads allowed
		
		nodeConfig.register("downloadAllowedDirs", new String[] {"all"}, sortOrder++, true, true, "NodeClientCore.downloadAllowedDirs", 
				"NodeClientCore.downloadAllowedDirsLong",
				new StringArrCallback() {

					public String[] get() {
						synchronized(NodeClientCore.this) {
							if(downloadAllowedEverywhere) return new String[] { "all" };
							String[] dirs = new String[downloadAllowedDirs.length + (includeDownloadDir ? 1 : 0) ];
							for(int i=0;i<downloadAllowedDirs.length;i++)
								dirs[i] = downloadAllowedDirs[i].getPath();
							if(includeDownloadDir)
								dirs[downloadAllowedDirs.length] = "downloads";
							return dirs;
						}
					}

					public void set(String[] val) throws InvalidConfigValueException {
						setDownloadAllowedDirs(val);
					}
			
		});
		if(node.lastVersion <= 1024)
			nodeConfig.fixOldDefault("downloadAllowedDirs", "downloads");
		setDownloadAllowedDirs(nodeConfig.getStringArr("downloadAllowedDirs"));
		
		nodeConfig.register("uploadAllowedDirs", new String[] {"all"}, sortOrder++, true, true, "NodeClientCore.uploadAllowedDirs", 
				"NodeClientCore.uploadAllowedDirsLong",
				new StringArrCallback() {

					public String[] get() {
						synchronized(NodeClientCore.this) {
							if(uploadAllowedEverywhere) return new String[] { "all" };
							String[] dirs = new String[uploadAllowedDirs.length];
							for(int i=0;i<uploadAllowedDirs.length;i++)
								dirs[i] = uploadAllowedDirs[i].getPath();
							return dirs;
						}
					}

					public void set(String[] val) throws InvalidConfigValueException {
						setUploadAllowedDirs(val);
					}
			
		});
		setUploadAllowedDirs(nodeConfig.getStringArr("uploadAllowedDirs"));
		
		archiveManager = new ArchiveManager(MAX_ARCHIVE_HANDLERS, MAX_CACHED_ARCHIVE_DATA, MAX_ARCHIVE_SIZE, MAX_ARCHIVED_FILE_SIZE, MAX_CACHED_ELEMENTS, random, tempFilenameGenerator);
		Logger.normal(this, "Initializing USK Manager");
		System.out.println("Initializing USK Manager");
		uskManager = new USKManager(this);
		
		healingQueue = new SimpleHealingQueue(requestStarters.chkPutScheduler,
				new InsertContext(tempBucketFactory, tempBucketFactory, persistentTempBucketFactory, 
						random, 0, 2, 1, 0, 0, new SimpleEventProducer(), 
						!Node.DONT_CACHE_LOCAL_REQUESTS, uskManager, backgroundBlockEncoder), RequestStarter.PREFETCH_PRIORITY_CLASS, 512 /* FIXME make configurable */);
		
		// FIXME remove this code, the new behaviour should be handled by all clients
		
		nodeConfig.register("ignoreTooManyPathComponents", false, sortOrder++, true, false, "NodeClientCore.ignoreTooManyPathComponents", 
				"NodeClientCore.ignoreTooManyPathComponentsLong", new BooleanCallback() {

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
		
		nodeConfig.register("lazyResume", false, sortOrder++, true, false, "NodeClientCore.lazyResume",
				"NodeClientCore.lazyResumeLong", new BooleanCallback() {

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
		
		nodeConfig.register("maxBackgroundUSKFetchers", "64", sortOrder++, true, false, "NodeClientCore.maxUSKFetchers",
				"NodeClientCore.maxUSKFetchersLong", new IntCallback() {
					public int get() {
						return maxBackgroundUSKFetchers;
					}
					public void set(int uskFetch) throws InvalidConfigValueException {
						if(uskFetch <= 0) throw new InvalidConfigValueException(l10n("maxUSKFetchersMustBeGreaterThanZero"));
							maxBackgroundUSKFetchers = uskFetch;
						}
					}
		);
		
		maxBackgroundUSKFetchers = nodeConfig.getInt("maxBackgroundUSKFetchers");
		
		
		// FIXME remove and remove related code when we can just block them.
		// REDFLAG normally we wouldn't use static variables to carry important non-final data, but in this
		// case it's temporary code which will be removed before 0.7.0.
		
		nodeConfig.register("allowInsecureCHKs", true, sortOrder++, true, false, "NodeClientCore.allowInsecureCHK", "NodeClientCore.allowInsecureCHKLong",
				new BooleanCallback() {

					public boolean get() {
						return Key.ALLOW_INSECURE_CLIENT_CHKS;
					}

					public void set(boolean val) throws InvalidConfigValueException {
						Key.ALLOW_INSECURE_CLIENT_CHKS = val;
					}
			
		});
		
		Key.ALLOW_INSECURE_CLIENT_CHKS = nodeConfig.getBoolean("allowInsecureCHKs");
		
		nodeConfig.register("allowInsecureSSKs", true, sortOrder++, true, false, "NodeClientCore.allowInsecureSSK", "NodeClientCore.allowInsecureSSKLong",
				new BooleanCallback() {

					public boolean get() {
						return Key.ALLOW_INSECURE_CLIENT_SSKS;
					}

					public void set(boolean val) throws InvalidConfigValueException {
						Key.ALLOW_INSECURE_CLIENT_SSKS = val;
					}
			
		});
		
		Key.ALLOW_INSECURE_CLIENT_SSKS = nodeConfig.getBoolean("allowInsecureSSKs");
		
	}

	private static String l10n(String key) {
		return L10n.getString("NodeClientCore."+key);
	}

	protected synchronized void setDownloadAllowedDirs(String[] val) {
		int x = 0;
		downloadAllowedEverywhere = false;
		includeDownloadDir = false;
		int i = 0;
		downloadAllowedDirs = new File[val.length];
		for(i=0;i<downloadAllowedDirs.length;i++) {
			String s = val[i];
			if(s.equals("downloads"))
				includeDownloadDir = true;
			else if(s.equals("all"))
				downloadAllowedEverywhere = true;
			else
				downloadAllowedDirs[x++] = new File(val[i]);
		}
		if(x != i) {
			File[] newDirs = new File[x];
			System.arraycopy(downloadAllowedDirs, 0, newDirs, 0, x);
			downloadAllowedDirs = newDirs;
		}
	}

	protected synchronized void setUploadAllowedDirs(String[] val) {
		int x = 0;
		int i = 0;
		uploadAllowedEverywhere = false;
		uploadAllowedDirs = new File[val.length];
		for(i=0;i<uploadAllowedDirs.length;i++) {
			String s = val[i];
			if(s.equals("all"))
				uploadAllowedEverywhere = true;
			else
				uploadAllowedDirs[x++] = new File(val[i]);
		}
		if(x != i) {
			File[] newDirs = new File[x];
			System.arraycopy(uploadAllowedDirs, 0, newDirs, 0, x);
			uploadAllowedDirs = newDirs;
		}
	}

	public void start(Config config) throws NodeInitException {

		persister.start();
		
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
		if(!node.lockUID(uid, false, false)) {
			Logger.error(this, "Could not lock UID just randomly generated: "+uid+" - probably indicates broken PRNG");
			throw new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR);
		}
		try {
		Object o = node.makeRequestSender(key.getNodeCHK(), node.maxHTL(), uid, null, node.getLocation(), false, localOnly, cache, ignoreStore);
		if(o instanceof CHKBlock) {
			try {
				node.unlockUID(uid, false, false);
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
				requestStarters.rejectedOverload(false, false);
				rejectedOverload = true;
			}

			int status = rs.getStatus();
			
			if(status == RequestSender.NOT_FINISHED) 
				continue;
			
	        if(status != RequestSender.TIMED_OUT && status != RequestSender.GENERATED_REJECTED_OVERLOAD && status != RequestSender.INTERNAL_ERROR) {
	        	if(logMINOR) Logger.minor(this, "CHK fetch cost "+rs.getTotalSentBytes()+ '/' +rs.getTotalReceivedBytes()+" bytes ("+status+ ')');
            	nodeStats.localChkFetchBytesSentAverage.report(rs.getTotalSentBytes());
            	nodeStats.localChkFetchBytesReceivedAverage.report(rs.getTotalReceivedBytes());
            	if(status == RequestSender.SUCCESS) {
            		// See comments above declaration of successful* : We don't report sent bytes here.
            		//nodeStats.successfulChkFetchBytesSentAverage.report(rs.getTotalSentBytes());
            		nodeStats.successfulChkFetchBytesReceivedAverage.report(rs.getTotalReceivedBytes());
            	}
	        }
			
			if((status == RequestSender.TIMED_OUT) ||
					(status == RequestSender.GENERATED_REJECTED_OVERLOAD)) {
				if(!rejectedOverload) {
					// See below
					requestStarters.rejectedOverload(false, false);
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
						requestStarters.requestCompleted(false, false);
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
		} finally {
			node.unlockUID(uid, false, false);
		}
	}

	ClientSSKBlock realGetSSK(ClientSSK key, boolean localOnly, boolean cache, boolean ignoreStore) throws LowLevelGetException {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		long startTime = System.currentTimeMillis();
		long uid = random.nextLong();
		if(!node.lockUID(uid, true, false)) {
			Logger.error(this, "Could not lock UID just randomly generated: "+uid+" - probably indicates broken PRNG");
			throw new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR);
		}
		try {
		Object o = node.makeRequestSender(key.getNodeKey(), node.maxHTL(), uid, null, node.getLocation(), false, localOnly, cache, ignoreStore);
		if(o instanceof SSKBlock) {
			try {
				SSKBlock block = (SSKBlock)o;
				key.setPublicKey(block.getPubKey());
				node.unlockUID(uid, true, false);
				return ClientSSKBlock.construct(block, key);
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
				requestStarters.rejectedOverload(true, false);
				rejectedOverload = true;
			}

			int status = rs.getStatus();
			
			if(status == RequestSender.NOT_FINISHED) 
				continue;

	        if(status != RequestSender.TIMED_OUT && status != RequestSender.GENERATED_REJECTED_OVERLOAD && status != RequestSender.INTERNAL_ERROR) {
            	if(logMINOR) Logger.minor(this, "SSK fetch cost "+rs.getTotalSentBytes()+ '/' +rs.getTotalReceivedBytes()+" bytes ("+status+ ')');
            	nodeStats.localSskFetchBytesSentAverage.report(rs.getTotalSentBytes());
            	nodeStats.localSskFetchBytesReceivedAverage.report(rs.getTotalReceivedBytes());
            	if(status == RequestSender.SUCCESS) {
            		// See comments above successfulSskFetchBytesSentAverage : we don't relay the data, so
            		// reporting the sent bytes would be inaccurate.
            		//nodeStats.successfulSskFetchBytesSentAverage.report(rs.getTotalSentBytes());
            		nodeStats.successfulSskFetchBytesReceivedAverage.report(rs.getTotalReceivedBytes());
            	}
	        }
			
			if((status == RequestSender.TIMED_OUT) ||
					(status == RequestSender.GENERATED_REJECTED_OVERLOAD)) {
				if(!rejectedOverload) {
					requestStarters.rejectedOverload(true, false);
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
						requestStarters.requestCompleted(true, false);
					requestStarters.sskRequestThrottle.successfulCompletion(rtt);
				}
			}
			
			if(rs.getStatus() == RequestSender.SUCCESS) {
				try {
					SSKBlock block = rs.getSSKBlock();
					key.setPublicKey(block.getPubKey());
					return ClientSSKBlock.construct(block, key);
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
		} finally {
			node.unlockUID(uid, true, false);
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
		if(!node.lockUID(uid, false, true)) {
			Logger.error(this, "Could not lock UID just randomly generated: "+uid+" - probably indicates broken PRNG");
			throw new LowLevelPutException(LowLevelPutException.INTERNAL_ERROR);
		}
		try {
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
				requestStarters.rejectedOverload(false, true);
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
				requestStarters.rejectedOverload(false, true);
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
					requestStarters.requestCompleted(false, true);
			}
		}
		
		int status = is.getStatus();
        if(status != CHKInsertSender.TIMED_OUT && status != CHKInsertSender.GENERATED_REJECTED_OVERLOAD && status != CHKInsertSender.INTERNAL_ERROR
        		&& status != CHKInsertSender.ROUTE_REALLY_NOT_FOUND) {
        	int sent = is.getTotalSentBytes();
        	int received = is.getTotalReceivedBytes();
        	if(logMINOR) Logger.minor(this, "Local CHK insert cost "+sent+ '/' +received+" bytes ("+status+ ')');
        	nodeStats.localChkInsertBytesSentAverage.report(sent);
        	nodeStats.localChkInsertBytesReceivedAverage.report(received);
        	if(status == CHKInsertSender.SUCCESS) {
        		// Only report Sent bytes because we did not receive the data.
        		nodeStats.successfulChkInsertBytesSentAverage.report(sent);
        		//nodeStats.successfulChkInsertBytesReceivedAverage.report(received);
        	}
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
		} finally {
			node.unlockUID(uid, false, true);
		}
	}

	public void realPutSSK(SSKBlock block, boolean cache) throws LowLevelPutException {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		SSKInsertSender is;
		long uid = random.nextLong();
		if(!node.lockUID(uid, true, true)) {
			Logger.error(this, "Could not lock UID just randomly generated: "+uid+" - probably indicates broken PRNG");
			throw new LowLevelPutException(LowLevelPutException.INTERNAL_ERROR);
		}
		try {
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
				requestStarters.rejectedOverload(true, true);
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
				requestStarters.requestCompleted(true, true);
				requestStarters.sskInsertThrottle.successfulCompletion(rtt);
			}
		}

		int status = is.getStatus();
		
        if(status != CHKInsertSender.TIMED_OUT && status != CHKInsertSender.GENERATED_REJECTED_OVERLOAD && status != CHKInsertSender.INTERNAL_ERROR
        		&& status != CHKInsertSender.ROUTE_REALLY_NOT_FOUND) {
        	int sent = is.getTotalSentBytes();
        	int received = is.getTotalReceivedBytes();
        	if(logMINOR) Logger.minor(this, "Local SSK insert cost "+sent+ '/' +received+" bytes ("+status+ ')');
        	nodeStats.localSskInsertBytesSentAverage.report(sent);
        	nodeStats.localSskInsertBytesReceivedAverage.report(received);
        	if(status == SSKInsertSender.SUCCESS) {
        		// Only report Sent bytes as we haven't received anything.
        		nodeStats.successfulSskInsertBytesSentAverage.report(sent);
        		//nodeStats.successfulSskInsertBytesReceivedAverage.report(received);
        	}
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
		} finally {
			node.unlockUID(uid, true, true);
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
		ssi.schedule();
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
	
	public int maxBackgroundUSKFetchers() {
		return maxBackgroundUSKFetchers;
	}
	
	public boolean lazyResume() {
		return lazyResume;
	}
	
	public boolean allowDownloadTo(File filename) {
		if(downloadAllowedEverywhere) return true;
		if(includeDownloadDir) {
			if(FileUtil.isParent(downloadDir, filename)) return true;
		}
		for(int i=0;i<downloadAllowedDirs.length;i++) {
			if(FileUtil.isParent(downloadAllowedDirs[i], filename)) return true;
		}
		return false;
	}

	public boolean allowUploadFrom(File filename) {
		if(uploadAllowedEverywhere) return true;
		for(int i=0;i<uploadAllowedDirs.length;i++) {
			if(FileUtil.isParent(uploadAllowedDirs[i], filename)) return true;
		}
		return false;
	}

	public SimpleFieldSet persistThrottlesToFieldSet() {
		return requestStarters.persistToFieldSet();
	}
}
