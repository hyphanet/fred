package freenet.node;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;

import org.tanukisoftware.wrapper.WrapperManager;

import com.db4o.ObjectContainer;
import com.db4o.ext.Db4oException;

import freenet.client.ArchiveManager;
import freenet.client.FECQueue;
import freenet.client.HighLevelSimpleClient;
import freenet.client.HighLevelSimpleClientImpl;
import freenet.client.InsertContext;
import freenet.client.async.BackgroundBlockEncoder;
import freenet.client.async.ClientContext;
import freenet.client.async.ClientRequestScheduler;
import freenet.client.async.ClientRequester;
import freenet.client.async.DBJob;
import freenet.client.async.DBJobRunner;
import freenet.client.async.DatabaseDisabledException;
import freenet.client.async.DatastoreChecker;
import freenet.client.async.HealingQueue;
import freenet.client.async.InsertCompressor;
import freenet.client.async.PersistentStatsPutter;
import freenet.client.async.SimpleHealingQueue;
import freenet.client.async.USKManager;
import freenet.client.events.SimpleEventProducer;
import freenet.client.filter.FilterCallback;
import freenet.client.filter.FoundURICallback;
import freenet.client.filter.GenericReadFilterCallback;
import freenet.client.filter.LinkFilterExceptionProvider;
import freenet.clients.http.FProxyToadlet;
import freenet.clients.http.SimpleToadletServer;
import freenet.config.Config;
import freenet.config.InvalidConfigValueException;
import freenet.config.NodeNeedRestartException;
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
import freenet.keys.FreenetURI;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.NodeSSK;
import freenet.keys.SSKBlock;
import freenet.keys.SSKVerifyException;
import freenet.l10n.NodeL10n;
import freenet.node.NodeRestartJobsQueue.RestartDBJob;
import freenet.node.SecurityLevels.PHYSICAL_THREAT_LEVEL;
import freenet.node.fcp.FCPServer;
import freenet.node.useralerts.SimpleUserAlert;
import freenet.node.useralerts.UserAlert;
import freenet.node.useralerts.UserAlertManager;
import freenet.store.KeyCollisionException;
import freenet.support.Base64;
import freenet.support.Executor;
import freenet.support.ExecutorIdleCallback;
import freenet.support.Logger;
import freenet.support.MutableBoolean;
import freenet.support.OOMHandler;
import freenet.support.OOMHook;
import freenet.support.PrioritizedSerialExecutor;
import freenet.support.SimpleFieldSet;
import freenet.support.SizeUtil;
import freenet.support.Ticker;
import freenet.support.api.BooleanCallback;
import freenet.support.api.IntCallback;
import freenet.support.api.LongCallback;
import freenet.support.api.StringArrCallback;
import freenet.support.compress.Compressor;
import freenet.support.compress.RealCompressor;
import freenet.support.io.FileUtil;
import freenet.support.io.FilenameGenerator;
import freenet.support.io.NativeThread;
import freenet.support.io.PersistentTempBucketFactory;
import freenet.support.io.TempBucketFactory;
import freenet.support.math.MersenneTwister;

/**
 * The connection between the node and the client layer.
 */
public class NodeClientCore implements Persistable, DBJobRunner, OOMHook, ExecutorIdleCallback {
	private static volatile boolean logMINOR;

	static {
		Logger.registerClass(NodeClientCore.class);
	}

	public final PersistentStatsPutter bandwidthStatsPutter;
	public final USKManager uskManager;
	public final ArchiveManager archiveManager;
	public final RequestStarterGroup requestStarters;
	private final HealingQueue healingQueue;
	public NodeRestartJobsQueue restartJobsQueue;
	/** Must be included as a hidden field in order for any dangerous HTTP operation to complete successfully. */
	public final String formPassword;
	final ProgramDirectory downloadsDir;
	private File[] downloadAllowedDirs;
	private boolean includeDownloadDir;
	private boolean downloadAllowedEverywhere;
	private boolean downloadDisabled;
	private File[] uploadAllowedDirs;
	private boolean uploadAllowedEverywhere;
	public final FilenameGenerator tempFilenameGenerator;
	public FilenameGenerator persistentFilenameGenerator;
	public final TempBucketFactory tempBucketFactory;
	public PersistentTempBucketFactory persistentTempBucketFactory;
	public final Node node;
	public final RequestTracker tracker;
	final NodeStats nodeStats;
	public final RandomSource random;
	final ProgramDirectory tempDir;	// Persistent temporary buckets
	final ProgramDirectory persistentTempDir;
	public FECQueue fecQueue;
	public final UserAlertManager alerts;
	final TextModeClientInterfaceServer tmci;
	TextModeClientInterface directTMCI;
	final FCPServer fcpServer;
	FProxyToadlet fproxyServlet;
	final SimpleToadletServer toadletContainer;
	public final BackgroundBlockEncoder backgroundBlockEncoder;
	public final RealCompressor compressor;
	/** If true, requests are resumed lazily i.e. startup does not block waiting for them. */
	protected final Persister persister;
	/** All client-layer database access occurs on a SerialExecutor, so that we don't need
	 * to have multiple parallel transactions. Advantages:
	 * - We never have two copies of the same object in RAM, and more broadly, we don't
	 *   need to worry about interactions between objects from different transactions.
	 * - Only one weak-reference cache for the database.
	 * - No need to refresh live objects.
	 * - Deactivation is simpler.
	 * Note that the priorities are thread priorities, not request priorities.
	 */
	public transient final PrioritizedSerialExecutor clientDatabaseExecutor;
	public final DatastoreChecker storeChecker;

	public transient final ClientContext clientContext;

	private static int maxBackgroundUSKFetchers;	// Client stuff that needs to be configged - FIXME
	static final int MAX_ARCHIVE_HANDLERS = 200; // don't take up much RAM... FIXME
	static final long MAX_CACHED_ARCHIVE_DATA = 32 * 1024 * 1024; // make a fixed fraction of the store by default? FIXME
	static final long MAX_ARCHIVED_FILE_SIZE = 1024 * 1024; // arbitrary... FIXME
	static final int MAX_CACHED_ELEMENTS = 256 * 1024; // equally arbitrary! FIXME hopefully we can cache many of these though
	/** Each FEC item can take a fair amount of RAM, since it's fully activated with all the buckets, potentially 256
	 * of them, so only cache a small number of them */
	private static final int FEC_QUEUE_CACHE_SIZE = 20;
	private UserAlert startingUpAlert;
	private RestartDBJob[] startupDatabaseJobs;
	private boolean alwaysCommit;

	NodeClientCore(Node node, Config config, SubConfig nodeConfig, SubConfig installConfig, int portNumber, int sortOrder, SimpleFieldSet oldConfig, SubConfig fproxyConfig, SimpleToadletServer toadlets, long nodeDBHandle, ObjectContainer container) throws NodeInitException {
		this.node = node;
		this.tracker = node.tracker;
		this.nodeStats = node.nodeStats;
		this.random = node.random;
		killedDatabase = container == null;
		if(killedDatabase)
			System.err.println("Database corrupted (before entering NodeClientCore)!");
		fecQueue = initFECQueue(node.nodeDBHandle, container, null);
		this.backgroundBlockEncoder = new BackgroundBlockEncoder();
		clientDatabaseExecutor = new PrioritizedSerialExecutor(NativeThread.NORM_PRIORITY, NativeThread.MAX_PRIORITY+1, NativeThread.NORM_PRIORITY, true, 30*1000, this, node.nodeStats);
		storeChecker = new DatastoreChecker(node);
		byte[] pwdBuf = new byte[16];
		random.nextBytes(pwdBuf);
		compressor = new RealCompressor(node.executor);
		this.formPassword = Base64.encode(pwdBuf);
		alerts = new UserAlertManager(this);
		if(container != null)
			initRestartJobs(nodeDBHandle, container);
		persister = new ConfigurablePersister(this, nodeConfig, "clientThrottleFile", "client-throttle.dat", sortOrder++, true, false,
			"NodeClientCore.fileForClientStats", "NodeClientCore.fileForClientStatsLong", node.ticker, node.getRunDir());

		SimpleFieldSet throttleFS = persister.read();
		if(logMINOR)
			Logger.minor(this, "Read throttleFS:\n" + throttleFS);

		if(logMINOR)
			Logger.minor(this, "Serializing RequestStarterGroup from:\n" + throttleFS);

		// Temp files

		this.tempDir = node.setupProgramDir(installConfig, "tempDir", node.runDir().file("temp").toString(),
		  "NodeClientCore.tempDir", "NodeClientCore.tempDirLong", nodeConfig);
		
		// FIXME remove back compatibility hack.
		File oldTemp = node.runDir().file("temp-"+node.getDarknetPortNumber());
		if(oldTemp.exists() && oldTemp.isDirectory() && !FileUtil.equals(tempDir.dir, oldTemp)) {
			System.err.println("Deleting old temporary dir: "+oldTemp);
			try {
				FileUtil.secureDeleteAll(oldTemp, new MersenneTwister(random.nextLong()));
			} catch (IOException e) {
				// Ignore.
			}
		}
		
		FileUtil.setOwnerRWX(getTempDir());

		try {
			tempFilenameGenerator = new FilenameGenerator(random, true, getTempDir(), "temp-");
		} catch(IOException e) {
			String msg = "Could not find or create temporary directory (filename generator)";
			throw new NodeInitException(NodeInitException.EXIT_BAD_DIR, msg);
		}

		this.bandwidthStatsPutter = new PersistentStatsPutter(this.node);
		if (container != null) {
			bandwidthStatsPutter.restorePreviousData(container);
			this.getTicker().queueTimedJob(new Runnable() {
				@Override
				public void run() {
					try {
						queue(bandwidthStatsPutter, NativeThread.LOW_PRIORITY, true);
						getTicker().queueTimedJob(this, "BandwidthStatsPutter", PersistentStatsPutter.OFFSET, false, true);
					} catch (DatabaseDisabledException e) {
						// Should be safe to ignore.
					}
				}
			}, PersistentStatsPutter.OFFSET);
		}

		uskManager = new USKManager(this);

		// Persistent temp files
		nodeConfig.register("encryptPersistentTempBuckets", true, sortOrder++, true, false, "NodeClientCore.encryptPersistentTempBuckets", "NodeClientCore.encryptPersistentTempBucketsLong", new BooleanCallback() {

			@Override
			public Boolean get() {
				return (persistentTempBucketFactory == null ? true : persistentTempBucketFactory.isEncrypting());
			}

			@Override
			public void set(Boolean val) throws InvalidConfigValueException {
				if (get().equals(val) || (persistentTempBucketFactory == null))
					        return;
				persistentTempBucketFactory.setEncryption(val);
			}
		});

		this.persistentTempDir = node.setupProgramDir(installConfig, "persistentTempDir", node.userDir().file("persistent-temp-"+portNumber).toString(),
		  "NodeClientCore.persistentTempDir", "NodeClientCore.persistentTempDirLong", nodeConfig);
		initPTBF(container, nodeConfig);

		// Allocate 10% of the RAM to the RAMBucketPool by default
		int defaultRamBucketPoolSize;
		long maxMemory = Runtime.getRuntime().maxMemory();
		if(maxMemory == Long.MAX_VALUE || maxMemory <= 0)
			defaultRamBucketPoolSize = 10;
		else {
			maxMemory /= (1024 * 1024);
			if(maxMemory <= 0) // Still bogus
				defaultRamBucketPoolSize = 10;
			else {
				// 10% of memory above 64MB, with a minimum of 1MB.
				defaultRamBucketPoolSize = Math.min(Integer.MAX_VALUE, (int)((maxMemory - 64) / 10));
				if(defaultRamBucketPoolSize <= 0) defaultRamBucketPoolSize = 1;
			}
		}

		// Max bucket size 5% of the total, minimum 32KB (one block, vast majority of buckets)
		long maxBucketSize = Math.max(32768, (defaultRamBucketPoolSize * 1024 * 1024) / 20);

		nodeConfig.register("maxRAMBucketSize", SizeUtil.formatSizeWithoutSpace(maxBucketSize), sortOrder++, true, false, "NodeClientCore.maxRAMBucketSize", "NodeClientCore.maxRAMBucketSizeLong", new LongCallback() {

			@Override
			public Long get() {
				return (tempBucketFactory == null ? 0 : tempBucketFactory.getMaxRAMBucketSize());
			}

			@Override
			public void set(Long val) throws InvalidConfigValueException {
				if (get().equals(val) || (tempBucketFactory == null))
					        return;
				tempBucketFactory.setMaxRAMBucketSize(val);
			}
		}, true);

		nodeConfig.register("RAMBucketPoolSize", defaultRamBucketPoolSize+"MiB", sortOrder++, true, false, "NodeClientCore.ramBucketPoolSize", "NodeClientCore.ramBucketPoolSizeLong", new LongCallback() {

			@Override
			public Long get() {
				return (tempBucketFactory == null ? 0 : tempBucketFactory.getMaxRamUsed());
			}

			@Override
			public void set(Long val) throws InvalidConfigValueException {
				if (get().equals(val) || (tempBucketFactory == null))
					        return;
				tempBucketFactory.setMaxRamUsed(val);
			}
		}, true);

		nodeConfig.register("encryptTempBuckets", true, sortOrder++, true, false, "NodeClientCore.encryptTempBuckets", "NodeClientCore.encryptTempBucketsLong", new BooleanCallback() {

			@Override
			public Boolean get() {
				return (tempBucketFactory == null ? true : tempBucketFactory.isEncrypting());
			}

			@Override
			public void set(Boolean val) throws InvalidConfigValueException {
				if (get().equals(val) || (tempBucketFactory == null))
					        return;
				tempBucketFactory.setEncryption(val);
			}
		});
		tempBucketFactory = new TempBucketFactory(node.executor, tempFilenameGenerator, nodeConfig.getLong("maxRAMBucketSize"), nodeConfig.getLong("RAMBucketPoolSize"), random, node.fastWeakRandom, nodeConfig.getBoolean("encryptTempBuckets"));

		archiveManager = new ArchiveManager(MAX_ARCHIVE_HANDLERS, MAX_CACHED_ARCHIVE_DATA, MAX_ARCHIVED_FILE_SIZE, MAX_CACHED_ELEMENTS, tempBucketFactory);

		healingQueue = new SimpleHealingQueue(
				new InsertContext(
						0, 2, 0, 0, new SimpleEventProducer(),
						false, Node.FORK_ON_CACHEABLE_DEFAULT, false, Compressor.DEFAULT_COMPRESSORDESCRIPTOR, 0, 0, InsertContext.CompatibilityMode.COMPAT_CURRENT), RequestStarter.PREFETCH_PRIORITY_CLASS, 512 /* FIXME make configurable */);

		clientContext = new ClientContext(node.bootID, nodeDBHandle, this, fecQueue, node.executor, backgroundBlockEncoder, archiveManager, persistentTempBucketFactory, tempBucketFactory, persistentTempBucketFactory, healingQueue, uskManager, random, node.fastWeakRandom, node.getTicker(), tempFilenameGenerator, persistentFilenameGenerator, compressor, storeChecker, toadlets);
		compressor.setClientContext(clientContext);
		storeChecker.setContext(clientContext);

		try {
			requestStarters = new RequestStarterGroup(node, this, portNumber, random, config, throttleFS, clientContext, nodeDBHandle, container);
		} catch (InvalidConfigValueException e1) {
			throw new NodeInitException(NodeInitException.EXIT_BAD_CONFIG, e1.toString());
		}
		
		
		clientContext.init(requestStarters, alerts);
		initKeys(container);

		node.securityLevels.addPhysicalThreatLevelListener(new SecurityLevelListener<PHYSICAL_THREAT_LEVEL>() {

			@Override
			public void onChange(PHYSICAL_THREAT_LEVEL oldLevel, PHYSICAL_THREAT_LEVEL newLevel) {
				if(newLevel == PHYSICAL_THREAT_LEVEL.LOW) {
					if(tempBucketFactory.isEncrypting()) {
						tempBucketFactory.setEncryption(false);
					}
					if(persistentTempBucketFactory != null) {
					if(persistentTempBucketFactory.isEncrypting()) {
						persistentTempBucketFactory.setEncryption(false);
					}
					}
				} else { // newLevel >= PHYSICAL_THREAT_LEVEL.NORMAL
					if(!tempBucketFactory.isEncrypting()) {
						tempBucketFactory.setEncryption(true);
					}
					if(persistentTempBucketFactory != null) {
					if(!persistentTempBucketFactory.isEncrypting()) {
						persistentTempBucketFactory.setEncryption(true);
					}
					}
				}
			}

		});

		// Downloads directory

		this.downloadsDir = node.setupProgramDir(nodeConfig, "downloadsDir", node.userDir().file("downloads").getPath(),
		  "NodeClientCore.downloadsDir", "NodeClientCore.downloadsDirLong", l10n("couldNotFindOrCreateDir"), (SubConfig)null);

		// Downloads allowed, uploads allowed

		nodeConfig.register("downloadAllowedDirs", new String[]{"all"}, sortOrder++, true, true, "NodeClientCore.downloadAllowedDirs",
			"NodeClientCore.downloadAllowedDirsLong",
			new StringArrCallback() {

				@Override
				public String[] get() {
					synchronized(NodeClientCore.this) {
						if(downloadAllowedEverywhere)
							return new String[]{"all"};
						String[] dirs = new String[downloadAllowedDirs.length + (includeDownloadDir ? 1 : 0)];
						for(int i = 0; i < downloadAllowedDirs.length; i++)
							dirs[i] = downloadAllowedDirs[i].getPath();
						if(includeDownloadDir)
							dirs[downloadAllowedDirs.length] = "downloads";
						return dirs;
					}
				}

				@Override
				public void set(String[] val) throws InvalidConfigValueException {
					setDownloadAllowedDirs(val);
				}
			});
		setDownloadAllowedDirs(nodeConfig.getStringArr("downloadAllowedDirs"));

		nodeConfig.register("uploadAllowedDirs", new String[]{"all"}, sortOrder++, true, true, "NodeClientCore.uploadAllowedDirs",
			"NodeClientCore.uploadAllowedDirsLong",
			new StringArrCallback() {

				@Override
				public String[] get() {
					synchronized(NodeClientCore.this) {
						if(uploadAllowedEverywhere)
							return new String[]{"all"};
						String[] dirs = new String[uploadAllowedDirs.length];
						for(int i = 0; i < uploadAllowedDirs.length; i++)
							dirs[i] = uploadAllowedDirs[i].getPath();
						return dirs;
					}
				}

				@Override
				public void set(String[] val) throws InvalidConfigValueException {
					setUploadAllowedDirs(val);
				}
			});
		setUploadAllowedDirs(nodeConfig.getStringArr("uploadAllowedDirs"));

		Logger.normal(this, "Initializing USK Manager");
		System.out.println("Initializing USK Manager");
		uskManager.init(clientContext);
		initUSK(container);

		nodeConfig.register("maxBackgroundUSKFetchers", "64", sortOrder++, true, false, "NodeClientCore.maxUSKFetchers",
			"NodeClientCore.maxUSKFetchersLong", new IntCallback() {

			@Override
			public Integer get() {
				return maxBackgroundUSKFetchers;
			}

			@Override
			public void set(Integer uskFetch) throws InvalidConfigValueException {
				if(uskFetch <= 0)
					throw new InvalidConfigValueException(l10n("maxUSKFetchersMustBeGreaterThanZero"));
				maxBackgroundUSKFetchers = uskFetch;
			}
		}, false);

		maxBackgroundUSKFetchers = nodeConfig.getInt("maxBackgroundUSKFetchers");


		// This is all part of construction, not of start().
		// Some plugins depend on it, so it needs to be *created* before they are started.

		// TMCI
		try {
			tmci = TextModeClientInterfaceServer.maybeCreate(node, this, config);
		} catch(IOException e) {
			e.printStackTrace();
			throw new NodeInitException(NodeInitException.EXIT_COULD_NOT_START_TMCI, "Could not start TMCI: " + e);
		}

		// FCP (including persistent requests so needs to start before FProxy)
		try {
			fcpServer = FCPServer.maybeCreate(node, this, node.config, container);
			clientContext.setDownloadCache(fcpServer);
			if(!killedDatabase)
				fcpServer.load(container);
		} catch(IOException e) {
			throw new NodeInitException(NodeInitException.EXIT_COULD_NOT_START_FCP, "Could not start FCP: " + e);
		} catch(InvalidConfigValueException e) {
			throw new NodeInitException(NodeInitException.EXIT_COULD_NOT_START_FCP, "Could not start FCP: " + e);
		}

		// FProxy
		// FIXME this is a hack, the real way to do this is plugins
		this.alerts.register(startingUpAlert = new SimpleUserAlert(true, l10n("startingUpTitle"), l10n("startingUp"), l10n("startingUpShort"), UserAlert.ERROR));
		this.alerts.register(new SimpleUserAlert(true, NodeL10n.getBase().getString("QueueToadlet.persistenceBrokenTitle"),
				NodeL10n.getBase().getString("QueueToadlet.persistenceBroken",
						new String[]{ "TEMPDIR", "DBFILE" },
						new String[]{ new File(FileUtil.getCanonicalFile(getPersistentTempDir()), File.separator).toString(), new File(FileUtil.getCanonicalFile(node.getUserDir()), "node.db4o").toString() }
				), NodeL10n.getBase().getString("QueueToadlet.persistenceBrokenShortAlert"), UserAlert.CRITICAL_ERROR)
				{
			@Override
			public boolean isValid() {
				synchronized(NodeClientCore.this) {
					if(!killedDatabase) return false;
				}
				if(NodeClientCore.this.node.awaitingPassword()) return false;
				if(NodeClientCore.this.node.isStopping()) return false;
				return true;
			}

			@Override
			public boolean userCanDismiss() {
				return false;
			}

		});
		toadletContainer = toadlets;
		toadletContainer.setCore(this);
		toadletContainer.setBucketFactory(tempBucketFactory);
		if(fecQueue == null) throw new NullPointerException();
		fecQueue.init(RequestStarter.NUMBER_OF_PRIORITY_CLASSES, FEC_QUEUE_CACHE_SIZE, clientContext.jobRunner, node.executor, clientContext);
		OOMHandler.addOOMHook(this);
		if(killedDatabase)
			System.err.println("Database corrupted (leaving NodeClientCore)!");

		nodeConfig.register("alwaysCommit", false, sortOrder++, true, false, "NodeClientCore.alwaysCommit", "NodeClientCore.alwaysCommitLong",
				new BooleanCallback() {

					@Override
					public Boolean get() {
						return alwaysCommit;
					}

					@Override
					public void set(Boolean val) throws InvalidConfigValueException, NodeNeedRestartException {
						alwaysCommit = val;
					}

		});
		alwaysCommit = nodeConfig.getBoolean("alwaysCommit");
	}

	private void initUSK(ObjectContainer container) {
		if(!killedDatabase) {
			try {
				uskManager.init(container);
			} catch (Db4oException e) {
				killedDatabase = true;
			}
		}
	}

	private void initKeys(ObjectContainer container) {
		if(!killedDatabase) {
			try {
				ClientRequestScheduler.loadKeyListeners(container, clientContext);
			} catch (Db4oException e) {
				killedDatabase = true;
			}
		}
		if(!killedDatabase) {
			try {
				InsertCompressor.load(container, clientContext);
				// FIXME get rid of this.
				if(container != null) {
					container.commit();
					ClientRequester.checkAll(container, clientContext);
				}
			} catch (Db4oException e) {
				killedDatabase = true;
			}
		}
	}

	private void initPTBF(ObjectContainer container, SubConfig nodeConfig) throws NodeInitException {
		PersistentTempBucketFactory ptbf = null;
		FilenameGenerator pfg = null;
		try {
			String prefix = "freenet-temp-";
			if(!killedDatabase) {
				ptbf = PersistentTempBucketFactory.load(getPersistentTempDir(), prefix, random, node.fastWeakRandom, container, node.nodeDBHandle, nodeConfig.getBoolean("encryptPersistentTempBuckets"), this, node.getTicker());
				ptbf.init(getPersistentTempDir(), prefix, random, node.fastWeakRandom);
				pfg = ptbf.fg;
			}
		} catch(IOException e2) {
			String msg = "Could not find or create persistent temporary directory: "+e2;
			e2.printStackTrace();
			throw new NodeInitException(NodeInitException.EXIT_BAD_DIR, msg);
		} catch (Db4oException e) {
			killedDatabase = true;
		} catch (Throwable t) {
			// Let the rest of the node start up but kill the database
			System.err.println("Failed to load persistent temporary buckets factory: "+t);
			t.printStackTrace();
			killedDatabase = true;
		}
		if(killedDatabase) {
			persistentTempBucketFactory = null;
			persistentFilenameGenerator = null;
		} else {
			persistentTempBucketFactory = ptbf;
			persistentFilenameGenerator = pfg;
			if(clientContext != null) {
				clientContext.setPersistentBucketFactory(persistentTempBucketFactory, persistentFilenameGenerator);
			}
		}
	}

	private void initRestartJobs(long nodeDBHandle, ObjectContainer container) {
		// Restart jobs are handled directly by NodeClientCore, so no need to deal with ClientContext.
		NodeRestartJobsQueue rq = null;
		try {
			if(!killedDatabase) rq = container == null ? null : NodeRestartJobsQueue.init(node.nodeDBHandle, container);
		} catch (Db4oException e) {
			killedDatabase = true;
		}
		restartJobsQueue = rq;
		RestartDBJob[] startupJobs = null;
		try {
			if(!killedDatabase)
				startupJobs = restartJobsQueue.getEarlyRestartDatabaseJobs(container);
		} catch (Db4oException e) {
			killedDatabase = true;
		}
		startupDatabaseJobs = startupJobs;
		if(startupDatabaseJobs != null &&
				startupDatabaseJobs.length > 0) {
			try {
				queue(startupJobRunner, NativeThread.HIGH_PRIORITY, false);
			} catch (DatabaseDisabledException e1) {
				// Impossible
			}
		}
		if(!killedDatabase) {
			try {
				restartJobsQueue.addLateRestartDatabaseJobs(this, container);
			} catch (Db4oException e) {
				killedDatabase = true;
			} catch (DatabaseDisabledException e) {
				// addLateRestartDatabaseJobs only modifies the database in case of a job being deleted without being removed.
				// So it is safe to just ignore this.
			}
		}

	}

	private FECQueue initFECQueue(long nodeDBHandle, ObjectContainer container, FECQueue oldQueue) {
		FECQueue q;
		try {
			oldFECQueue = oldQueue;
			if(!killedDatabase) q = FECQueue.create(node.nodeDBHandle, container, oldQueue);
			else q = new FECQueue(node.nodeDBHandle);
		} catch (Db4oException e) {
			killedDatabase = true;
			q = new FECQueue(node.nodeDBHandle);
		}
		return q;
	}

	private FECQueue oldFECQueue;

	boolean lateInitDatabase(long nodeDBHandle, ObjectContainer container) throws NodeInitException {
		System.out.println("Late database initialisation: starting middle phase");
		synchronized(this) {
			killedDatabase = false;
		}
		// Don't actually start the database thread yet, messy concurrency issues.
		lateInitFECQueue(nodeDBHandle, container);
		initRestartJobs(nodeDBHandle, container);
		initPTBF(container, node.config.get("node"));
		requestStarters.lateStart(this, nodeDBHandle, container);
		// Must create the CRSCore's before telling them to load stuff.
		initKeys(container);
		if(!killedDatabase)
			fcpServer.load(container);
		synchronized(this) {
			if(killedDatabase) {
				startupDatabaseJobs = null;
				fecQueue = oldFECQueue;
				clientContext.setFECQueue(oldFECQueue);
				persistentTempBucketFactory = null;
				persistentFilenameGenerator = null;
				clientContext.setPersistentBucketFactory(null, null);
				return false;
			}
		}

		bandwidthStatsPutter.restorePreviousData(container);
		this.getTicker().queueTimedJob(new Runnable() {
			@Override
			public void run() {
				try {
					queue(bandwidthStatsPutter, NativeThread.LOW_PRIORITY, false);
					getTicker().queueTimedJob(this, "BandwidthStatsPutter", PersistentStatsPutter.OFFSET, false, true);
				} catch (DatabaseDisabledException e) {
					// Should be safe to ignore.
				}
			}
		}, PersistentStatsPutter.OFFSET);

		// CONCURRENCY: We need everything to have hit its various memory locations.
		// How to ensure this?
		// FIXME This is a hack!!
		// I guess the standard solution would be to make ClientContext members volatile etc?
		// That sucks though ... they are only changed ONCE, and they are used constantly.
		// Also existing transient requests won't care about the changes; what we must guarantee
		// is that new persistent jobs will be accepted.
		node.getTicker().queueTimedJob(new Runnable() {

			@Override
			public void run() {
				clientDatabaseExecutor.start(node.executor, "Client database access thread");
			}

		}, 1000);
		System.out.println("Late database initialisation completed.");
		return true;
	}

	private void lateInitFECQueue(long nodeDBHandle, ObjectContainer container) {
		fecQueue = initFECQueue(nodeDBHandle, container, fecQueue);
		clientContext.setFECQueue(fecQueue);
	}

	private static String l10n(String key) {
		return NodeL10n.getBase().getString("NodeClientCore." + key);
	}

	public boolean isDownloadDisabled() {
		return downloadDisabled;
	}

	protected synchronized void setDownloadAllowedDirs(String[] val) {
		int x = 0;
		downloadAllowedEverywhere = false;
		includeDownloadDir = false;
		downloadDisabled = false;
		int i = 0;
		downloadAllowedDirs = new File[val.length];
		for(i = 0; i < downloadAllowedDirs.length; i++) {
			String s = val[i];
			if(s.equals("downloads")) includeDownloadDir = true;
			else if(s.equals("all")) downloadAllowedEverywhere = true;
			else downloadAllowedDirs[x++] = new File(val[i]);
		}
		if(x != i) {
			downloadAllowedDirs = Arrays.copyOf(downloadAllowedDirs, x);
		}
		if(i == 0) {
			downloadDisabled = true;
		}
	}

	protected synchronized void setUploadAllowedDirs(String[] val) {
		int x = 0;
		int i = 0;
		uploadAllowedEverywhere = false;
		uploadAllowedDirs = new File[val.length];
		for(i = 0; i < uploadAllowedDirs.length; i++) {
			String s = val[i];
			if(s.equals("all"))
				uploadAllowedEverywhere = true;
			else
				uploadAllowedDirs[x++] = new File(val[i]);
		}
		if(x != i) {
			uploadAllowedDirs = Arrays.copyOf(uploadAllowedDirs, x);
		}
	}

	public void start(Config config) throws NodeInitException {
		backgroundBlockEncoder.setContext(clientContext);
		node.executor.execute(backgroundBlockEncoder, "Background block encoder");
		try {
			clientContext.jobRunner.queue(new DBJob() {

				@Override
				public String toString() {
					return "Init ArchiveManager";
				}

				@Override
				public boolean run(ObjectContainer container, ClientContext context) {
					ArchiveManager.init(container, context, context.nodeDBHandle);
					return false;
				}

			}, NativeThread.MAX_PRIORITY, false);
		} catch (DatabaseDisabledException e) {
			// Safe to ignore
		}

		persister.start();

		requestStarters.start();

		storeChecker.start(node.executor, "Datastore checker");
		if(fcpServer != null)
			fcpServer.maybeStart();
		if(tmci != null)
			tmci.start();
		backgroundBlockEncoder.runPersistentQueue(clientContext);
		node.executor.execute(compressor, "Compression scheduler");

		node.executor.execute(new PrioRunnable() {

			@Override
			public void run() {
				Logger.normal(this, "Resuming persistent requests");
				if(persistentTempBucketFactory != null)
					persistentTempBucketFactory.completedInit();
				node.pluginManager.start(node.config);
				node.ipDetector.ipDetectorManager.start();
				// FIXME most of the work is done after this point on splitfile starter threads.
				// So do we want to make a fuss?
				// FIXME but a better solution is real request resuming.
				Logger.normal(this, "Completed startup: All persistent requests resumed or restarted");
				alerts.unregister(startingUpAlert);
			}

			@Override
			public int getPriority() {
				return NativeThread.LOW_PRIORITY;
			}
		}, "Startup completion thread");

		if(!killedDatabase)
			clientDatabaseExecutor.start(node.executor, "Client database access thread");
	}

	private int startupDatabaseJobsDone = 0;

	private DBJob startupJobRunner = new DBJob() {

		@Override
		public String toString() {
			return "Run startup jobs";
		}

		@Override
		public boolean run(ObjectContainer container, ClientContext context) {
			RestartDBJob job = startupDatabaseJobs[startupDatabaseJobsDone];
			try {
				container.activate(job.job, 1);
				// Remove before execution, to allow it to re-add itself if it wants to
				System.err.println("Cleaning up after restart: "+job.job);
				restartJobsQueue.removeRestartJob(job.job, job.prio, container);
				job.job.run(container, context);
				container.commit();
			} catch (Throwable t) {
				Logger.error(this, "Caught "+t+" in startup job "+job, t);
				// Try again next time
				restartJobsQueue.queueRestartJob(job.job, job.prio, container, true);
			}
			startupDatabaseJobsDone++;
			if(startupDatabaseJobsDone == startupDatabaseJobs.length)
				startupDatabaseJobs = null;
			else
				try {
					context.jobRunner.queue(startupJobRunner, NativeThread.HIGH_PRIORITY, false);
				} catch (DatabaseDisabledException e) {
					// Do nothing
				}
			return true;
		}

	};

	public interface SimpleRequestSenderCompletionListener {

		public void completed(boolean success);
	}

	/** UID -1 is used internally, so never generate it.
	 * It is not however a problem if a node does use it; it will slow its messages down
	 * by them being round-robin'ed in PeerMessageQueue with messages with no UID, that's
	 * all. */
	long makeUID() {
		while(true) {
			long uid = random.nextLong();
			if(uid != -1) return uid;
		}
	}

	/** Start an asynchronous fetch for the key, which will complete by calling 
	 * tripPendingKey() if successful, as well as calling the listener in most cases.
	 * @param key The key to fetch.
	 * @param offersOnly If true, only fetch the key from nodes that have offered it, using GetOfferedKeys,
	 * don't do a normal fetch for it.
	 * @param listener The listener is called if we start a request and fail to fetch, and also in most
	 * cases on success or on not starting one. FIXME it may not always be called e.g. on fetching the data
	 * from the datastore - is this a problem?
	 * @param canReadClientCache Can this request read the client-cache?
	 * @param canWriteClientCache Can this request write the client-cache?
	 * @param htl The HTL to start the request at. See the caller, this can be modified in the case of 
	 * fetching an offered key.
	 * @param realTimeFlag Is this a real-time request? False = this is a bulk request.
	 * @param localOnly If true, only check the datastore, don't create a request if nothing is found.
	 * @param ignoreStore If true, don't check the datastore, create a request immediately.
	 */
	public void asyncGet(final Key key, boolean offersOnly, final RequestCompletionListener listener, boolean canReadClientCache, boolean canWriteClientCache, final boolean realTimeFlag, boolean localOnly, boolean ignoreStore) {
		final long uid = makeUID();
		final boolean isSSK = key instanceof NodeSSK;
		final RequestTag tag = new RequestTag(isSSK, RequestTag.START.ASYNC_GET, null, realTimeFlag, uid, node);
		if(!tracker.lockUID(uid, isSSK, false, false, true, realTimeFlag, tag)) {
			Logger.error(this, "Could not lock UID just randomly generated: " + uid + " - probably indicates broken PRNG");
			listener.onFailed(new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR, "Could not lock random UID - serious PRNG problem???"));
			return;
		}
		tag.setAccepted();
		short htl = node.maxHTL();
		// If another node requested it within the ULPR period at a lower HTL, that may allow
		// us to cache it in the datastore. Find the lowest HTL fetching the key in that period,
		// and use that for purposes of deciding whether to cache it in the store.
		if(offersOnly) {
			htl = node.failureTable.minOfferedHTL(key, htl);
			if(logMINOR) Logger.minor(this, "Using old HTL for GetOfferedKey: "+htl);
		}
		final long startTime = System.currentTimeMillis();
		asyncGet(key, offersOnly, uid, new RequestSenderListener() {

			private boolean rejectedOverload;
			
			@Override
			public void onCHKTransferBegins() {
				// Ignore
			}

			@Override
			public void onReceivedRejectOverload() {
				synchronized(this) {
					if(rejectedOverload) return;
					rejectedOverload = true;
				}
				requestStarters.rejectedOverload(isSSK, false, realTimeFlag);
			}
			
			@Override
			public void onDataFoundLocally() {
				tag.unlockHandler();
				listener.onSucceeded();
			}

			/** The RequestSender finished.
			 * @param status The completion status.
			 * @param fromOfferedKey
			 */
			@Override
			public void onRequestSenderFinished(int status, boolean fromOfferedKey, RequestSender rs) {
				tag.unlockHandler();
				
				if(rs.abortedDownstreamTransfers())
					status = RequestSender.TRANSFER_FAILED;

				if(status == RequestSender.NOT_FINISHED) {
					Logger.error(this, "Bogus status in onRequestSenderFinished for "+rs, new Exception("error"));
					listener.onFailed(new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR));
					return;
				}
				
				boolean rejectedOverload;
				synchronized(this) {
					rejectedOverload = this.rejectedOverload;
				}

				if(status != RequestSender.TIMED_OUT && status != RequestSender.GENERATED_REJECTED_OVERLOAD && status != RequestSender.INTERNAL_ERROR) {
					if(logMINOR)
						Logger.minor(this, (isSSK ? "SSK" : "CHK") + " fetch cost " + rs.getTotalSentBytes() + '/' + rs.getTotalReceivedBytes() + " bytes (" + status + ')');
					(isSSK ? nodeStats.localSskFetchBytesSentAverage : nodeStats.localChkFetchBytesSentAverage).report(rs.getTotalSentBytes());
					(isSSK ? nodeStats.localSskFetchBytesReceivedAverage : nodeStats.localChkFetchBytesReceivedAverage).report(rs.getTotalReceivedBytes());
					if(status == RequestSender.SUCCESS)
						// See comments above declaration of successful* : We don't report sent bytes here.
						//nodeStats.successfulChkFetchBytesSentAverage.report(rs.getTotalSentBytes());
						(isSSK ? nodeStats.successfulSskFetchBytesReceivedAverage : nodeStats.successfulChkFetchBytesReceivedAverage).report(rs.getTotalReceivedBytes());
				}

				if((status == RequestSender.TIMED_OUT) ||
					(status == RequestSender.GENERATED_REJECTED_OVERLOAD)) {
					if(!rejectedOverload) {
						// If onRejectedOverload() is going to happen,
						// it should have happened before this callback is called, so
						// we don't need to check again here.
						requestStarters.rejectedOverload(isSSK, false, realTimeFlag);
						rejectedOverload = true;
						long rtt = System.currentTimeMillis() - startTime;
						double targetLocation=key.toNormalizedDouble();
						if(isSSK) {
							node.nodeStats.reportSSKOutcome(rtt, false, realTimeFlag);
						} else {
							node.nodeStats.reportCHKOutcome(rtt, false, targetLocation, realTimeFlag);
						}
					}
				} else
					if(rs.hasForwarded() &&
						((status == RequestSender.DATA_NOT_FOUND) ||
						(status == RequestSender.RECENTLY_FAILED) ||
						(status == RequestSender.SUCCESS) ||
						(status == RequestSender.ROUTE_NOT_FOUND) ||
						(status == RequestSender.VERIFY_FAILURE) ||
						(status == RequestSender.GET_OFFER_VERIFY_FAILURE))) {
						long rtt = System.currentTimeMillis() - startTime;
						double targetLocation=key.toNormalizedDouble();
						if(!rejectedOverload)
							requestStarters.requestCompleted(isSSK, false, key, realTimeFlag);
						// Count towards RTT even if got a RejectedOverload - but not if timed out.
						requestStarters.getThrottle(isSSK, false, realTimeFlag).successfulCompletion(rtt);
						if(isSSK) {
							node.nodeStats.reportSSKOutcome(rtt, status == RequestSender.SUCCESS, realTimeFlag);
						} else {
							node.nodeStats.reportCHKOutcome(rtt, status == RequestSender.SUCCESS, targetLocation, realTimeFlag);
						}
						if(status == RequestSender.SUCCESS) {
							Logger.minor(this, "Successful " + (isSSK ? "SSK" : "CHK") + " fetch took "+rtt);
						}
					}

				if(status == RequestSender.SUCCESS)
					// FIXME how to identify failed to decode and report it back to the client layer??? do we even need to???
					listener.onSucceeded();
				else {
					switch(status) {
						case RequestSender.NOT_FINISHED:
							Logger.error(this, "RS still running in get" + (isSSK ? "SSK" : "CHK") + "!: " + rs);
							listener.onFailed(new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR));
							return;
						case RequestSender.DATA_NOT_FOUND:
							listener.onFailed(new LowLevelGetException(LowLevelGetException.DATA_NOT_FOUND));
							return;
						case RequestSender.RECENTLY_FAILED:
							listener.onFailed(new LowLevelGetException(LowLevelGetException.RECENTLY_FAILED));
							return;
						case RequestSender.ROUTE_NOT_FOUND:
							listener.onFailed(new LowLevelGetException(LowLevelGetException.ROUTE_NOT_FOUND));
							return;
						case RequestSender.TRANSFER_FAILED:
						case RequestSender.GET_OFFER_TRANSFER_FAILED:
							listener.onFailed(new LowLevelGetException(LowLevelGetException.TRANSFER_FAILED));
							return;
						case RequestSender.VERIFY_FAILURE:
						case RequestSender.GET_OFFER_VERIFY_FAILURE:
							listener.onFailed(new LowLevelGetException(LowLevelGetException.VERIFY_FAILED));
							return;
						case RequestSender.GENERATED_REJECTED_OVERLOAD:
						case RequestSender.TIMED_OUT:
							listener.onFailed(new LowLevelGetException(LowLevelGetException.REJECTED_OVERLOAD));
							return;
						case RequestSender.INTERNAL_ERROR:
							listener.onFailed(new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR));
							return;
						default:
							Logger.error(this, "Unknown RequestSender code in get"+ (isSSK ? "SSK" : "CHK") +": " + status + " on " + rs);
							listener.onFailed(new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR));
							return;
					}
				}
			}

			@Override
			public void onAbortDownstreamTransfers(int reason, String desc) {
				// Ignore, onRequestSenderFinished will also be called.
			}

			@Override
			public void onNotStarted(boolean internalError) {
				if(internalError)
					listener.onFailed(new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR));
				else
					listener.onFailed(new LowLevelGetException(LowLevelGetException.DATA_NOT_FOUND_IN_STORE));
			}
		}, tag, canReadClientCache, canWriteClientCache, htl, realTimeFlag, localOnly, ignoreStore);
	}

	/**
	 * Start an asynchronous fetch of the key in question, which will complete to the datastore.
	 * It will not decode the data because we don't provide a ClientKey. It will not return
	 * anything and will run asynchronously. Caller is responsible for unlocking the UID.
	 * @param key The key being fetched.
	 * @param offersOnly If true, only fetch the key from nodes that have offered it, using GetOfferedKeys,
	 * don't do a normal fetch for it.
	 * @param uid The UID of the request. This should already be locked, see the tag.
	 * @param tag The RequestTag for the request. In case of an error when starting it we will unlock it,
	 * but in other cases the listener should unlock it.
	 * @param listener Will be called by the request sender, if a request is started.
	 * However, for example, if we fetch it from the store, it will be returned via the
	 * tripPendingKeys mechanism.
	 * @param canReadClientCache Can this request read the client-cache?
	 * @param canWriteClientCache Can this request write the client-cache?
	 * @param htl The HTL to start the request at. See the caller, this can be modified in the case of 
	 * fetching an offered key.
	 * @param realTimeFlag Is this a real-time request? False = this is a bulk request.
	 * @param localOnly If true, only check the datastore, don't create a request if nothing is found.
	 * @param ignoreStore If true, don't check the datastore, create a request immediately.
	 */
	void asyncGet(Key key, boolean offersOnly, long uid, RequestSenderListener listener, RequestTag tag, boolean canReadClientCache, boolean canWriteClientCache, short htl, boolean realTimeFlag, boolean localOnly, boolean ignoreStore) {
		try {
			Object o = node.makeRequestSender(key, htl, uid, tag, null, localOnly, ignoreStore, offersOnly, canReadClientCache, canWriteClientCache, realTimeFlag);
			if(o instanceof KeyBlock) {
				tag.setServedFromDatastore();
				listener.onDataFoundLocally();
				return; // Already have it.
			}
			if(o == null) {
				listener.onNotStarted(false);
				tag.unlockHandler();
				return;
			}
			RequestSender rs = (RequestSender) o;
			rs.addListener(listener);
			if(rs.uid != uid)
				tag.unlockHandler();
			// Else it has started a request.
			if(logMINOR)
				Logger.minor(this, "Started " + o + " for " + uid + " for " + key);
		} catch(RuntimeException e) {
			Logger.error(this, "Caught error trying to start request: " + e, e);
			listener.onNotStarted(true);
		} catch(Error e) {
			Logger.error(this, "Caught error trying to start request: " + e, e);
			listener.onNotStarted(true);
		}
	}

	public ClientKeyBlock realGetKey(ClientKey key, boolean localOnly, boolean ignoreStore, boolean canWriteClientCache, boolean realTimeFlag) throws LowLevelGetException {
		if(key instanceof ClientCHK)
			return realGetCHK((ClientCHK) key, localOnly, ignoreStore, canWriteClientCache, realTimeFlag);
		else if(key instanceof ClientSSK)
			return realGetSSK((ClientSSK) key, localOnly, ignoreStore, canWriteClientCache, realTimeFlag);
		else
			throw new IllegalArgumentException("Not a CHK or SSK: " + key);
	}

	/**
	 * Fetch a CHK.
	 * @param key
	 * @param localOnly
	 * @param ignoreStore
	 * @param canWriteClientCache Can we write to the client cache? This is a local request, so
	 * we can always read from it, but some clients will want to override to avoid polluting it.
	 * @return The fetched block.
	 * @throws LowLevelGetException
	 */
	ClientCHKBlock realGetCHK(ClientCHK key, boolean localOnly, boolean ignoreStore, boolean canWriteClientCache, boolean realTimeFlag) throws LowLevelGetException {
		long startTime = System.currentTimeMillis();
		long uid = makeUID();
		RequestTag tag = new RequestTag(false, RequestTag.START.LOCAL, null, realTimeFlag, uid, node);
		if(!tracker.lockUID(uid, false, false, false, true, realTimeFlag, tag)) {
			Logger.error(this, "Could not lock UID just randomly generated: " + uid + " - probably indicates broken PRNG");
			throw new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR);
		}
		tag.setAccepted();
		RequestSender rs = null;
		try {
			Object o = node.makeRequestSender(key.getNodeCHK(), node.maxHTL(), uid, tag, null, localOnly, ignoreStore, false, true, canWriteClientCache, realTimeFlag);
			if(o instanceof CHKBlock)
				try {
					tag.setServedFromDatastore();
					return new ClientCHKBlock((CHKBlock) o, key);
				} catch(CHKVerifyException e) {
					Logger.error(this, "Does not verify: " + e, e);
					throw new LowLevelGetException(LowLevelGetException.DECODE_FAILED);
				}
			if(o == null)
				throw new LowLevelGetException(LowLevelGetException.DATA_NOT_FOUND_IN_STORE);
			rs = (RequestSender) o;
			boolean rejectedOverload = false;
			short waitStatus = 0;
			while(true) {
				waitStatus = rs.waitUntilStatusChange(waitStatus);
				if((!rejectedOverload) && (waitStatus & RequestSender.WAIT_REJECTED_OVERLOAD) != 0) {
					// See below; inserts count both
					requestStarters.rejectedOverload(false, false, realTimeFlag);
					rejectedOverload = true;
				}

				int status = rs.getStatus();

				if(rs.abortedDownstreamTransfers())
					status = RequestSender.TRANSFER_FAILED;

				if(status == RequestSender.NOT_FINISHED)
					continue;

				if(status != RequestSender.TIMED_OUT && status != RequestSender.GENERATED_REJECTED_OVERLOAD && status != RequestSender.INTERNAL_ERROR) {
					if(logMINOR)
						Logger.minor(this, "CHK fetch cost " + rs.getTotalSentBytes() + '/' + rs.getTotalReceivedBytes() + " bytes (" + status + ')');
					nodeStats.localChkFetchBytesSentAverage.report(rs.getTotalSentBytes());
					nodeStats.localChkFetchBytesReceivedAverage.report(rs.getTotalReceivedBytes());
					if(status == RequestSender.SUCCESS)
						// See comments above declaration of successful* : We don't report sent bytes here.
						//nodeStats.successfulChkFetchBytesSentAverage.report(rs.getTotalSentBytes());
						nodeStats.successfulChkFetchBytesReceivedAverage.report(rs.getTotalReceivedBytes());
				}

				if((status == RequestSender.TIMED_OUT) ||
					(status == RequestSender.GENERATED_REJECTED_OVERLOAD)) {
					if(!rejectedOverload) {
						// See below
						requestStarters.rejectedOverload(false, false, realTimeFlag);
						rejectedOverload = true;
						long rtt = System.currentTimeMillis() - startTime;
						double targetLocation=key.getNodeCHK().toNormalizedDouble();
						node.nodeStats.reportCHKOutcome(rtt, false, targetLocation, realTimeFlag);
					}
				} else
					if(rs.hasForwarded() &&
						((status == RequestSender.DATA_NOT_FOUND) ||
						(status == RequestSender.RECENTLY_FAILED) ||
						(status == RequestSender.SUCCESS) ||
						(status == RequestSender.ROUTE_NOT_FOUND) ||
						(status == RequestSender.VERIFY_FAILURE) ||
						(status == RequestSender.GET_OFFER_VERIFY_FAILURE))) {
						long rtt = System.currentTimeMillis() - startTime;
						double targetLocation=key.getNodeCHK().toNormalizedDouble();
						if(!rejectedOverload)
							requestStarters.requestCompleted(false, false, key.getNodeKey(true), realTimeFlag);
						// Count towards RTT even if got a RejectedOverload - but not if timed out.
						requestStarters.getThrottle(false, false, realTimeFlag).successfulCompletion(rtt);
						node.nodeStats.reportCHKOutcome(rtt, status == RequestSender.SUCCESS, targetLocation, realTimeFlag);
						if(status == RequestSender.SUCCESS) {
							Logger.minor(this, "Successful CHK fetch took "+rtt);
						}
					}

				if(status == RequestSender.SUCCESS)
					try {
						return new ClientCHKBlock(rs.getPRB().getBlock(), rs.getHeaders(), key, true);
					} catch(CHKVerifyException e) {
						Logger.error(this, "Does not verify: " + e, e);
						throw new LowLevelGetException(LowLevelGetException.DECODE_FAILED);
					} catch(AbortedException e) {
						Logger.error(this, "Impossible: " + e, e);
						throw new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR);
					}
				else {
					switch(status) {
						case RequestSender.NOT_FINISHED:
							Logger.error(this, "RS still running in getCHK!: " + rs);
							throw new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR);
						case RequestSender.DATA_NOT_FOUND:
							throw new LowLevelGetException(LowLevelGetException.DATA_NOT_FOUND);
						case RequestSender.RECENTLY_FAILED:
							throw new LowLevelGetException(LowLevelGetException.RECENTLY_FAILED);
						case RequestSender.ROUTE_NOT_FOUND:
							throw new LowLevelGetException(LowLevelGetException.ROUTE_NOT_FOUND);
						case RequestSender.TRANSFER_FAILED:
						case RequestSender.GET_OFFER_TRANSFER_FAILED:
							throw new LowLevelGetException(LowLevelGetException.TRANSFER_FAILED);
						case RequestSender.VERIFY_FAILURE:
						case RequestSender.GET_OFFER_VERIFY_FAILURE:
							throw new LowLevelGetException(LowLevelGetException.VERIFY_FAILED);
						case RequestSender.GENERATED_REJECTED_OVERLOAD:
						case RequestSender.TIMED_OUT:
							throw new LowLevelGetException(LowLevelGetException.REJECTED_OVERLOAD);
						case RequestSender.INTERNAL_ERROR:
							throw new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR);
						default:
							Logger.error(this, "Unknown RequestSender code in getCHK: " + status + " on " + rs);
							throw new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR);
					}
				}
			}
		} finally {
			tag.unlockHandler();
		}
	}

	ClientSSKBlock realGetSSK(ClientSSK key, boolean localOnly, boolean ignoreStore, boolean canWriteClientCache, boolean realTimeFlag) throws LowLevelGetException {
		long startTime = System.currentTimeMillis();
		long uid = makeUID();
		RequestTag tag = new RequestTag(true, RequestTag.START.LOCAL, null, realTimeFlag, uid, node);
		if(!tracker.lockUID(uid, true, false, false, true, realTimeFlag, tag)) {
			Logger.error(this, "Could not lock UID just randomly generated: " + uid + " - probably indicates broken PRNG");
			throw new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR);
		}
		tag.setAccepted();
		RequestSender rs = null;
		try {
			Object o = node.makeRequestSender(key.getNodeKey(true), node.maxHTL(), uid, tag, null, localOnly, ignoreStore, false, true, canWriteClientCache, realTimeFlag);
			if(o instanceof SSKBlock)
				try {
					tag.setServedFromDatastore();
					SSKBlock block = (SSKBlock) o;
					key.setPublicKey(block.getPubKey());
					return ClientSSKBlock.construct(block, key);
				} catch(SSKVerifyException e) {
					Logger.error(this, "Does not verify: " + e, e);
					throw new LowLevelGetException(LowLevelGetException.DECODE_FAILED);
				}
			if(o == null)
				throw new LowLevelGetException(LowLevelGetException.DATA_NOT_FOUND_IN_STORE);
			rs = (RequestSender) o;
			boolean rejectedOverload = false;
			short waitStatus = 0;
			while(true) {
				waitStatus = rs.waitUntilStatusChange(waitStatus);
				if((!rejectedOverload) && (waitStatus & RequestSender.WAIT_REJECTED_OVERLOAD) != 0) {
					requestStarters.rejectedOverload(true, false, realTimeFlag);
					rejectedOverload = true;
				}

				int status = rs.getStatus();

				if(status == RequestSender.NOT_FINISHED)
					continue;

				if(status != RequestSender.TIMED_OUT && status != RequestSender.GENERATED_REJECTED_OVERLOAD && status != RequestSender.INTERNAL_ERROR) {
					if(logMINOR)
						Logger.minor(this, "SSK fetch cost " + rs.getTotalSentBytes() + '/' + rs.getTotalReceivedBytes() + " bytes (" + status + ')');
					nodeStats.localSskFetchBytesSentAverage.report(rs.getTotalSentBytes());
					nodeStats.localSskFetchBytesReceivedAverage.report(rs.getTotalReceivedBytes());
					if(status == RequestSender.SUCCESS)
						// See comments above successfulSskFetchBytesSentAverage : we don't relay the data, so
						// reporting the sent bytes would be inaccurate.
						//nodeStats.successfulSskFetchBytesSentAverage.report(rs.getTotalSentBytes());
						nodeStats.successfulSskFetchBytesReceivedAverage.report(rs.getTotalReceivedBytes());
				}

				long rtt = System.currentTimeMillis() - startTime;
				if((status == RequestSender.TIMED_OUT) ||
					(status == RequestSender.GENERATED_REJECTED_OVERLOAD)) {
					if(!rejectedOverload) {
						requestStarters.rejectedOverload(true, false, realTimeFlag);
						rejectedOverload = true;
					}
					node.nodeStats.reportSSKOutcome(rtt, false, realTimeFlag);
				} else
					if(rs.hasForwarded() &&
						((status == RequestSender.DATA_NOT_FOUND) ||
						(status == RequestSender.RECENTLY_FAILED) ||
						(status == RequestSender.SUCCESS) ||
						(status == RequestSender.ROUTE_NOT_FOUND) ||
						(status == RequestSender.VERIFY_FAILURE) ||
						(status == RequestSender.GET_OFFER_VERIFY_FAILURE))) {

						if(!rejectedOverload)
							requestStarters.requestCompleted(true, false, key.getNodeKey(true), realTimeFlag);
						// Count towards RTT even if got a RejectedOverload - but not if timed out.
						requestStarters.getThrottle(true, false, realTimeFlag).successfulCompletion(rtt);
						node.nodeStats.reportSSKOutcome(rtt, status == RequestSender.SUCCESS, realTimeFlag);
					}

				if(rs.getStatus() == RequestSender.SUCCESS)
					try {
						SSKBlock block = rs.getSSKBlock();
						key.setPublicKey(block.getPubKey());
						return ClientSSKBlock.construct(block, key);
					} catch(SSKVerifyException e) {
						Logger.error(this, "Does not verify: " + e, e);
						throw new LowLevelGetException(LowLevelGetException.DECODE_FAILED);
					}
				else
					switch(rs.getStatus()) {
						case RequestSender.NOT_FINISHED:
							Logger.error(this, "RS still running in getCHK!: " + rs);
							throw new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR);
						case RequestSender.DATA_NOT_FOUND:
							throw new LowLevelGetException(LowLevelGetException.DATA_NOT_FOUND);
						case RequestSender.RECENTLY_FAILED:
							throw new LowLevelGetException(LowLevelGetException.RECENTLY_FAILED);
						case RequestSender.ROUTE_NOT_FOUND:
							throw new LowLevelGetException(LowLevelGetException.ROUTE_NOT_FOUND);
						case RequestSender.TRANSFER_FAILED:
						case RequestSender.GET_OFFER_TRANSFER_FAILED:
							Logger.error(this, "WTF? Transfer failed on an SSK? on " + uid);
							throw new LowLevelGetException(LowLevelGetException.TRANSFER_FAILED);
						case RequestSender.VERIFY_FAILURE:
						case RequestSender.GET_OFFER_VERIFY_FAILURE:
							throw new LowLevelGetException(LowLevelGetException.VERIFY_FAILED);
						case RequestSender.GENERATED_REJECTED_OVERLOAD:
						case RequestSender.TIMED_OUT:
							throw new LowLevelGetException(LowLevelGetException.REJECTED_OVERLOAD);
						case RequestSender.INTERNAL_ERROR:
						default:
							Logger.error(this, "Unknown RequestSender code in getCHK: " + rs.getStatus() + " on " + rs);
							throw new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR);
					}
			}
		} finally {
			tag.unlockHandler();
		}
	}

	/**
	 * Start a local request to insert a block. Note that this is a KeyBlock not a ClientKeyBlock
	 * mainly because of random reinserts.
	 * @param block
	 * @param canWriteClientCache
	 * @throws LowLevelPutException
	 */
	public void realPut(KeyBlock block, boolean canWriteClientCache, boolean forkOnCacheable, boolean preferInsert, boolean ignoreLowBackoff, boolean realTimeFlag) throws LowLevelPutException {
		if(block instanceof CHKBlock)
			realPutCHK((CHKBlock) block, canWriteClientCache, forkOnCacheable, preferInsert, ignoreLowBackoff, realTimeFlag);
		else if(block instanceof SSKBlock)
			realPutSSK((SSKBlock) block, canWriteClientCache, forkOnCacheable, preferInsert, ignoreLowBackoff, realTimeFlag);
		else
			throw new IllegalArgumentException("Unknown put type " + block.getClass());
	}

	public void realPutCHK(CHKBlock block, boolean canWriteClientCache, boolean forkOnCacheable, boolean preferInsert, boolean ignoreLowBackoff, boolean realTimeFlag) throws LowLevelPutException {
		byte[] data = block.getData();
		byte[] headers = block.getHeaders();
		PartiallyReceivedBlock prb = new PartiallyReceivedBlock(Node.PACKETS_IN_BLOCK, Node.PACKET_SIZE, data);
		CHKInsertSender is;
		long uid = makeUID();
		InsertTag tag = new InsertTag(false, InsertTag.START.LOCAL, null, realTimeFlag, uid, node);
		if(!tracker.lockUID(uid, false, true, false, true, realTimeFlag, tag)) {
			Logger.error(this, "Could not lock UID just randomly generated: " + uid + " - probably indicates broken PRNG");
			throw new LowLevelPutException(LowLevelPutException.INTERNAL_ERROR);
		}
		tag.setAccepted();
		try {
			long startTime = System.currentTimeMillis();
			is = node.makeInsertSender(block.getKey(),
				node.maxHTL(), uid, tag, null, headers, prb, false, canWriteClientCache, forkOnCacheable, preferInsert, ignoreLowBackoff, realTimeFlag);
			boolean hasReceivedRejectedOverload = false;
			// Wait for status
			while(true) {
				synchronized(is) {
					if(is.getStatus() == CHKInsertSender.NOT_FINISHED)
						try {
							is.wait(5 * 1000);
						} catch(InterruptedException e) {
							// Ignore
						}
					if(is.getStatus() != CHKInsertSender.NOT_FINISHED)
						break;
				}
				if((!hasReceivedRejectedOverload) && is.receivedRejectedOverload()) {
					hasReceivedRejectedOverload = true;
					requestStarters.rejectedOverload(false, true, realTimeFlag);
				}
			}

			// Wait for completion
			while(true) {
				synchronized(is) {
					if(is.completed())
						break;
					try {
						is.wait(10 * 1000);
					} catch(InterruptedException e) {
						// Go around again
					}
				}
				if(is.anyTransfersFailed() && (!hasReceivedRejectedOverload)) {
					hasReceivedRejectedOverload = true; // not strictly true but same effect
					requestStarters.rejectedOverload(false, true, realTimeFlag);
				}
			}

			if(logMINOR)
				Logger.minor(this, "Completed " + uid + " overload=" + hasReceivedRejectedOverload + ' ' + is.getStatusString());

			// Finished?
			if(!hasReceivedRejectedOverload)
				// Is it ours? Did we send a request?
				if(is.sentRequest() && (is.uid == uid) && ((is.getStatus() == CHKInsertSender.ROUTE_NOT_FOUND) || (is.getStatus() == CHKInsertSender.SUCCESS))) {
					// It worked!
					long endTime = System.currentTimeMillis();
					long len = endTime - startTime;

					// RejectedOverload requests count towards RTT (timed out ones don't).
					requestStarters.getThrottle(false, true, realTimeFlag).successfulCompletion(len);
					requestStarters.requestCompleted(false, true, block.getKey(), realTimeFlag);
				}

			// Get status explicitly, *after* completed(), so that it will be RECEIVE_FAILED if the receive failed.
			int status = is.getStatus();
			if(status != CHKInsertSender.TIMED_OUT && status != CHKInsertSender.GENERATED_REJECTED_OVERLOAD && status != CHKInsertSender.INTERNAL_ERROR && status != CHKInsertSender.ROUTE_REALLY_NOT_FOUND) {
				int sent = is.getTotalSentBytes();
				int received = is.getTotalReceivedBytes();
				if(logMINOR)
					Logger.minor(this, "Local CHK insert cost " + sent + '/' + received + " bytes (" + status + ')');
				nodeStats.localChkInsertBytesSentAverage.report(sent);
				nodeStats.localChkInsertBytesReceivedAverage.report(received);
				if(status == CHKInsertSender.SUCCESS)
					// Only report Sent bytes because we did not receive the data.
					nodeStats.successfulChkInsertBytesSentAverage.report(sent);
			}

			boolean deep = node.shouldStoreDeep(block.getKey(), null, is == null ? new PeerNode[0] : is.getRoutedTo());
			try {
				node.store(block, deep, canWriteClientCache, false, false);
			} catch (KeyCollisionException e) {
				// CHKs don't collide
			}

			if(status == CHKInsertSender.SUCCESS) {
				Logger.normal(this, "Succeeded inserting " + block);
				return;
			} else {
				String msg = "Failed inserting " + block + " : " + is.getStatusString();
				if(status == CHKInsertSender.ROUTE_NOT_FOUND)
					msg += " - this is normal on small networks; the data will still be propagated, but it can't find the 20+ nodes needed for full success";
				if(is.getStatus() != CHKInsertSender.ROUTE_NOT_FOUND)
					Logger.error(this, msg);
				else
					Logger.normal(this, msg);
				switch(is.getStatus()) {
					case CHKInsertSender.NOT_FINISHED:
						Logger.error(this, "IS still running in putCHK!: " + is);
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
						Logger.error(this, "Unknown CHKInsertSender code in putCHK: " + is.getStatus() + " on " + is);
						throw new LowLevelPutException(LowLevelPutException.INTERNAL_ERROR);
				}
			}
		} finally {
			tag.unlockHandler();
		}
	}

	public void realPutSSK(SSKBlock block, boolean canWriteClientCache, boolean forkOnCacheable, boolean preferInsert, boolean ignoreLowBackoff, boolean realTimeFlag) throws LowLevelPutException {
		SSKInsertSender is;
		long uid = makeUID();
		InsertTag tag = new InsertTag(true, InsertTag.START.LOCAL, null, realTimeFlag, uid, node);
		if(!tracker.lockUID(uid, true, true, false, true, realTimeFlag, tag)) {
			Logger.error(this, "Could not lock UID just randomly generated: " + uid + " - probably indicates broken PRNG");
			throw new LowLevelPutException(LowLevelPutException.INTERNAL_ERROR);
		}
		tag.setAccepted();
		try {
			long startTime = System.currentTimeMillis();
			// Be consistent: use the client cache to check for collisions as this is a local insert.
			SSKBlock altBlock = node.fetch(block.getKey(), false, true, canWriteClientCache, false, false, null);
			if(altBlock != null && !altBlock.equals(block))
				throw new LowLevelPutException(altBlock);
			is = node.makeInsertSender(block,
				node.maxHTL(), uid, tag, null, false, canWriteClientCache, false, forkOnCacheable, preferInsert, ignoreLowBackoff, realTimeFlag);
			boolean hasReceivedRejectedOverload = false;
			// Wait for status
			while(true) {
				synchronized(is) {
					if(is.getStatus() == SSKInsertSender.NOT_FINISHED)
						try {
							is.wait(5 * 1000);
						} catch(InterruptedException e) {
							// Ignore
						}
					if(is.getStatus() != SSKInsertSender.NOT_FINISHED)
						break;
				}
				if((!hasReceivedRejectedOverload) && is.receivedRejectedOverload()) {
					hasReceivedRejectedOverload = true;
					requestStarters.rejectedOverload(true, true, realTimeFlag);
				}
			}

			// Wait for completion
			while(true) {
				synchronized(is) {
					if(is.getStatus() != SSKInsertSender.NOT_FINISHED)
						break;
					try {
						is.wait(10 * 1000);
					} catch(InterruptedException e) {
						// Go around again
					}
				}
			}

			if(logMINOR)
				Logger.minor(this, "Completed " + uid + " overload=" + hasReceivedRejectedOverload + ' ' + is.getStatusString());

			// Finished?
			if(!hasReceivedRejectedOverload)
				// Is it ours? Did we send a request?
				if(is.sentRequest() && (is.uid == uid) && ((is.getStatus() == SSKInsertSender.ROUTE_NOT_FOUND) || (is.getStatus() == SSKInsertSender.SUCCESS))) {
					// It worked!
					long endTime = System.currentTimeMillis();
					long rtt = endTime - startTime;
					requestStarters.requestCompleted(true, true, block.getKey(), realTimeFlag);
					requestStarters.getThrottle(true, true, realTimeFlag).successfulCompletion(rtt);
				}

			int status = is.getStatus();

			if(status != CHKInsertSender.TIMED_OUT && status != CHKInsertSender.GENERATED_REJECTED_OVERLOAD && status != CHKInsertSender.INTERNAL_ERROR && status != CHKInsertSender.ROUTE_REALLY_NOT_FOUND) {
				int sent = is.getTotalSentBytes();
				int received = is.getTotalReceivedBytes();
				if(logMINOR)
					Logger.minor(this, "Local SSK insert cost " + sent + '/' + received + " bytes (" + status + ')');
				nodeStats.localSskInsertBytesSentAverage.report(sent);
				nodeStats.localSskInsertBytesReceivedAverage.report(received);
				if(status == SSKInsertSender.SUCCESS)
					// Only report Sent bytes as we haven't received anything.
					nodeStats.successfulSskInsertBytesSentAverage.report(sent);
			}

			boolean deep = node.shouldStoreDeep(block.getKey(), null, is == null ? new PeerNode[0] : is.getRoutedTo());

			if(is.hasCollided()) {
				SSKBlock collided = is.getBlock();
				// Store it locally so it can be fetched immediately, and overwrites any locally inserted.
				try {
					// Has collided *on the network*, not locally.
					node.storeInsert(collided, deep, true, canWriteClientCache, false);
				} catch(KeyCollisionException e) {
					// collision race?
					// should be impossible.
					Logger.normal(this, "collision race? is="+is, e);
				}
				throw new LowLevelPutException(collided);
			} else
				try {
					node.storeInsert(block, deep, false, canWriteClientCache, false);
				} catch(KeyCollisionException e) {
					LowLevelPutException failed = new LowLevelPutException(LowLevelPutException.COLLISION);
					NodeSSK key = block.getKey();
					KeyBlock collided = node.fetch(key, true, canWriteClientCache, false, false, null);
					if(collided == null) {
						Logger.error(this, "Collided but no key?!");
						// Could be a race condition.
						try {
							node.store(block, false, canWriteClientCache, false, false);
						} catch (KeyCollisionException e2) {
							Logger.error(this, "Collided but no key and still collided!");
							throw new LowLevelPutException(LowLevelPutException.INTERNAL_ERROR, "Collided, can't find block, but still collides!", e);
						}
					}

					failed.setCollidedBlock(collided);
					throw failed;
				}


			if(status == SSKInsertSender.SUCCESS) {
				Logger.normal(this, "Succeeded inserting " + block);
				return;
			} else {
				String msg = "Failed inserting " + block + " : " + is.getStatusString();
				if(status == CHKInsertSender.ROUTE_NOT_FOUND)
					msg += " - this is normal on small networks; the data will still be propagated, but it can't find the 20+ nodes needed for full success";
				if(is.getStatus() != SSKInsertSender.ROUTE_NOT_FOUND)
					Logger.error(this, msg);
				else
					Logger.normal(this, msg);
				switch(is.getStatus()) {
					case SSKInsertSender.NOT_FINISHED:
						Logger.error(this, "IS still running in putCHK!: " + is);
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
						Logger.error(this, "Unknown CHKInsertSender code in putSSK: " + is.getStatus() + " on " + is);
						throw new LowLevelPutException(LowLevelPutException.INTERNAL_ERROR);
				}
			}
		} finally {
			tag.unlockHandler();
		}
	}

	/** @deprecated Only provided for compatibility with old plugins! Plugins must specify! */
	@Deprecated
	public HighLevelSimpleClient makeClient(short prioClass) {
		return makeClient(prioClass, false, false);
	}

	/** @deprecated Only provided for compatibility with old plugins! Plugins must specify! */
	@Deprecated
	public HighLevelSimpleClient makeClient(short prioClass, boolean forceDontIgnoreTooManyPathComponents) {
		return makeClient(prioClass, forceDontIgnoreTooManyPathComponents, false);
	}

	/**
	 * @param prioClass The priority to run requests at.
	 * @param realTimeFlag If true, requests are latency-optimised. If false, they are
	 * throughput-optimised. Fewer latency-optimised ("real time") requests are accepted
	 * but their transfers are faster. Latency-optimised requests are expected to be bursty,
	 * whereas throughput-optimised (bulk) requests can be constant.
	 */
	public HighLevelSimpleClient makeClient(short prioClass, boolean forceDontIgnoreTooManyPathComponents, boolean realTimeFlag) {
		return new HighLevelSimpleClientImpl(this, tempBucketFactory, random, prioClass, forceDontIgnoreTooManyPathComponents, realTimeFlag);
	}

	public FCPServer getFCPServer() {
		return fcpServer;
	}

	public FProxyToadlet getFProxy() {
		return fproxyServlet;
	}

	public SimpleToadletServer getToadletContainer() {
		return toadletContainer;
	}

	/**
	 * Returns the link filter exception provider of the node. At the moment
	 * this is the {@link #getToadletContainer() toadlet container}.
	 *
	 * @return The link filter exception provider
	 */
	public LinkFilterExceptionProvider getLinkFilterExceptionProvider() {
		return toadletContainer;
	}

	public TextModeClientInterfaceServer getTextModeClientInterface() {
		return tmci;
	}

	public void setFProxy(FProxyToadlet fproxy) {
		this.fproxyServlet = fproxy;
	}

	public TextModeClientInterface getDirectTMCI() {
		return directTMCI;
	}

	public void setDirectTMCI(TextModeClientInterface i) {
		this.directTMCI = i;
	}

	public File getDownloadsDir() {
		return downloadsDir.dir();
	}

	public ProgramDirectory downloadsDir() {
		return downloadsDir;
	}

	public HealingQueue getHealingQueue() {
		return healingQueue;
	}

	public void queueRandomReinsert(KeyBlock block) {
		SimpleSendableInsert ssi = new SimpleSendableInsert(this, block, RequestStarter.MAXIMUM_PRIORITY_CLASS);
		if(logMINOR)
			Logger.minor(this, "Queueing random reinsert for " + block + " : " + ssi);
		ssi.schedule();
	}

	public void storeConfig() {
		Logger.normal(this, "Trying to write config to disk", new Exception("debug"));
		node.config.store();
	}

	public boolean isTestnetEnabled() {
		return Node.isTestnetEnabled();
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
		if(logMINOR)
			Logger.minor(this, "Creating filter callback: " + uri + ", " + cb);
		return new GenericReadFilterCallback(uri, cb,null, toadletContainer);
	}

	public int maxBackgroundUSKFetchers() {
		return maxBackgroundUSKFetchers;
	}

	public boolean allowDownloadTo(File filename) {
		PHYSICAL_THREAT_LEVEL physicalThreatLevel = node.securityLevels.getPhysicalThreatLevel();
		if(physicalThreatLevel == PHYSICAL_THREAT_LEVEL.MAXIMUM) return false;
		synchronized(this) {
			if(downloadAllowedEverywhere) return true;
			if(includeDownloadDir && FileUtil.isParent(getDownloadsDir(), filename)) return true;
			for(File dir : downloadAllowedDirs) {
				if(dir == null) {
					// Debug mysterious NPE...
					Logger.error(this, "Null in upload allowed dirs???");
					continue;
				}
				if(FileUtil.isParent(dir, filename)) return true;
			}
			return false;
		}
	}

	public synchronized boolean allowUploadFrom(File filename) {
		if(uploadAllowedEverywhere) return true;
		for(File dir : uploadAllowedDirs) {
			if(dir == null) {
				// Debug mysterious NPE...
				Logger.error(this, "Null in upload allowed dirs???");
				continue;
			}
			if(FileUtil.isParent(dir, filename)) return true;
		}
		return false;
	}

	public synchronized File[] getAllowedDownloadDirs() {
		return downloadAllowedDirs;
	}

	public synchronized File[] getAllowedUploadDirs() {
		return uploadAllowedDirs;
	}

	@Override
	public SimpleFieldSet persistThrottlesToFieldSet() {
		return requestStarters.persistToFieldSet();
	}

	public Ticker getTicker() {
		return node.getTicker();
	}

	public Executor getExecutor() {
		return node.executor;
	}

	public File getPersistentTempDir() {
		return persistentTempDir.dir();
	}

	public File getTempDir() {
		return tempDir.dir();
	}

	/** Queue the offered key. */
	public void queueOfferedKey(Key key, boolean realTime) {
		ClientRequestScheduler sched = requestStarters.getScheduler(key instanceof NodeSSK, false, realTime);
		sched.queueOfferedKey(key, realTime);
	}

	public void dequeueOfferedKey(Key key) {
		ClientRequestScheduler sched = requestStarters.getScheduler(key instanceof NodeSSK, false, false);
		sched.dequeueOfferedKey(key);
		sched = requestStarters.getScheduler(key instanceof NodeSSK, false, true);
		sched.dequeueOfferedKey(key);
	}

	public FreenetURI[] getBookmarkURIs() {
		return toadletContainer.getBookmarkURIs();
	}

	public long countTransientQueuedRequests() {
		return requestStarters.countTransientQueuedRequests();
	}

	@Override
	public void queue(final DBJob job, int priority, boolean checkDupes) throws DatabaseDisabledException{
		synchronized(this) {
			if(killedDatabase) throw new DatabaseDisabledException();
		}
		if(checkDupes)
			this.clientDatabaseExecutor.executeNoDupes(new DBJobWrapper(job), priority, ""+job);
		else
			this.clientDatabaseExecutor.execute(new DBJobWrapper(job), priority, ""+job);
	}

	private boolean killedDatabase = false;

	private long lastCommitted = System.currentTimeMillis();

	static final int MAX_COMMIT_INTERVAL = 30*1000;

	static final int SOON_COMMIT_INTERVAL = 5*1000;

	class DBJobWrapper implements Runnable {

		DBJobWrapper(DBJob job) {
			this.job = job;
			if(job == null) throw new NullPointerException();
		}

		final DBJob job;

		@Override
		public void run() {

			try {
				synchronized(NodeClientCore.this) {
					if(killedDatabase) {
						Logger.error(this, "Database killed already, not running job");
						return;
					}
				}
				if(job == null) throw new NullPointerException();
				if(node == null) throw new NullPointerException();
				boolean commit = job.run(node.db, clientContext);
				boolean killed;
				synchronized(NodeClientCore.this) {
					killed = killedDatabase;
					if(!killed) {
						long now = System.currentTimeMillis();
						if(now - lastCommitted > MAX_COMMIT_INTERVAL) {
							lastCommitted = now;
							commit = true;
						} else if(commitSoon && now - lastCommitted > SOON_COMMIT_INTERVAL) {
							commitSoon = false;
							lastCommitted = now;
							commit = true;
						} else if(commitSoon && !clientDatabaseExecutor.anyQueued()) {
							commitSoon = false;
							lastCommitted = now;
							commit = true;
						}
						if(alwaysCommit)
							commit = true;
						if(commitThisTransaction) {
							commit = true;
							commitThisTransaction = false;
						}
					}
				}
				if(killed) {
					node.db.rollback();
					return;
				} else if(commit) {
					persistentTempBucketFactory.preCommit(node.db);
					node.db.commit();
					synchronized(NodeClientCore.this) {
						lastCommitted = System.currentTimeMillis();
					}
					if(logMINOR) Logger.minor(this, "COMMITTED");
					persistentTempBucketFactory.postCommit(node.db);
				}
			} catch (Throwable t) {
				if(t instanceof OutOfMemoryError) {
					synchronized(NodeClientCore.this) {
						killedDatabase = true;
					}
					OOMHandler.handleOOM((OutOfMemoryError) t);
				} else {
					Logger.error(this, "Failed to run database job "+job+" : caught "+t, t);
				}
				boolean killed;
				synchronized(NodeClientCore.this) {
					killed = killedDatabase;
				}
				if(killed) {
					node.db.rollback();
				}
			}
		}

		@Override
		public int hashCode() {
			return job == null ? 0 : job.hashCode();
		}

		@Override
		public boolean equals(Object o) {
			if(!(o instanceof DBJobWrapper)) return false;
			DBJobWrapper cmp = (DBJobWrapper) o;
			return (cmp.job == job);
		}

		@Override
		public String toString() {
			return "DBJobWrapper:"+job;
		}

	}

	@Override
	public boolean onDatabaseThread() {
		return clientDatabaseExecutor.onThread();
	}

	@Override
	public int getQueueSize(int priority) {
		return clientDatabaseExecutor.getQueueSize(priority);
	}

	@Override
	public void handleLowMemory() throws Exception {
		// Ignore
	}

	@Override
	public void handleOutOfMemory() throws Exception {
		synchronized(this) {
			killedDatabase = true;
		}
		WrapperManager.requestThreadDump();
		System.err.println("Out of memory: Emergency shutdown to protect database integrity in progress...");
		WrapperManager.restart();
		System.exit(NodeInitException.EXIT_OUT_OF_MEMORY_PROTECTING_DATABASE);
	}

	/**
	 * Queue a job to be run soon after startup. The job must delete itself.
	 */
	@Override
	public void queueRestartJob(DBJob job, int priority, ObjectContainer container, boolean early) throws DatabaseDisabledException {
		synchronized(this) {
			if(killedDatabase) throw new DatabaseDisabledException();
		}
		restartJobsQueue.queueRestartJob(job, priority, container, early);
	}

	@Override
	public void removeRestartJob(DBJob job, int priority, ObjectContainer container) throws DatabaseDisabledException {
		synchronized(this) {
			if(killedDatabase) throw new DatabaseDisabledException();
		}
		restartJobsQueue.removeRestartJob(job, priority, container);
	}

	@Override
	public void runBlocking(final DBJob job, int priority) throws DatabaseDisabledException {
		if(clientDatabaseExecutor.onThread()) {
			job.run(node.db, clientContext);
		} else {
			final MutableBoolean finished = new MutableBoolean();
			queue(new DBJob() {

				@Override
				public boolean run(ObjectContainer container, ClientContext context) {
					try {
						return job.run(container, context);
					} finally {
						synchronized(finished) {
							finished.value = true;
							finished.notifyAll();
						}
					}
				}

				@Override
				public String toString() {
					return job.toString();
				}

			}, priority, false);
			synchronized(finished) {
				while(!finished.value) {
					try {
						finished.wait();
					} catch (InterruptedException e) {
						// Ignore
					}
				}
			}
		}
	}

	public boolean objectCanNew(ObjectContainer container) {
		Logger.error(this, "Not storing NodeClientCore in database", new Exception("error"));
		return false;
	}

	public synchronized void killDatabase() {
		killedDatabase = true;
	}

	@Override
	public synchronized boolean killedDatabase() {
		return killedDatabase;
	}

	@Override
	public void onIdle() {
		synchronized(NodeClientCore.this) {
			if(killedDatabase) return;
		}
		persistentTempBucketFactory.preCommit(node.db);
		node.db.commit();
		synchronized(NodeClientCore.this) {
			lastCommitted = System.currentTimeMillis();
		}
		if(logMINOR) Logger.minor(this, "COMMITTED");
		persistentTempBucketFactory.postCommit(node.db);
	}

	private boolean commitThisTransaction;

	@Override
	public synchronized void setCommitThisTransaction() {
		commitThisTransaction = true;
	}

	private boolean commitSoon;

	@Override
	public synchronized void setCommitSoon() {
		commitSoon = true;
	}

	public static int getMaxBackgroundUSKFetchers() {
		return maxBackgroundUSKFetchers;
	}

	/* FIXME SECURITY When/if introduce tunneling or similar mechanism for starting requests
	 * at a distance this will need to be reconsidered. See the comments on the caller in
	 * RequestHandler (onAbort() handler). */
	public boolean wantKey(Key key) {
		boolean isSSK = key instanceof NodeSSK;
		if(this.clientContext.getFetchScheduler(isSSK, true).wantKey(key)) return true;
		if(this.clientContext.getFetchScheduler(isSSK, false).wantKey(key)) return true;
		return false;
	}

	public long checkRecentlyFailed(Key key, boolean realTime) {
		RecentlyFailedReturn r = new RecentlyFailedReturn();
		// We always decrement when we start a request. This feeds into the
		// routing decision. Depending on our decrementAtMax flag, it may or
		// may not actually go down one hop. But if we don't use it here then
		// this won't be comparable to the decisions taken by the RequestSender,
		// so we will keep on selecting and RF'ing locally, and wasting send
		// slots and CPU. FIXME SECURITY/NETWORK: Reconsider if we ever decide
		// not to decrement on the originator.
		short origHTL = node.decrementHTL(null, node.maxHTL());
		node.peers.closerPeer(null, new HashSet<PeerNode>(), key.toNormalizedDouble(), true, false, -1, null, 2.0, key, origHTL, 0, true, realTime, r, false, System.currentTimeMillis(), node.enableNewLoadManagement(realTime));
		return r.recentlyFailed();
	}

}
