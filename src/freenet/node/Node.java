/*
 * Freenet 0.7 node.
 * 
 * Designed primarily for darknet operation, but should also be usable
 * in open mode eventually.
 */
package freenet.node;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Random;

import org.spaceroots.mantissa.random.MersenneTwister;
import org.tanukisoftware.wrapper.WrapperManager;

import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.EnvironmentMutableConfig;
import com.sleepycat.je.StatsConfig;
import com.sleepycat.je.util.DbDump;

import freenet.client.FetchContext;
import freenet.config.EnumerableOptionCallback;
import freenet.config.FreenetFilePersistentConfig;
import freenet.config.InvalidConfigValueException;
import freenet.config.LongOption;
import freenet.config.PersistentConfig;
import freenet.config.SubConfig;
import freenet.crypt.DSAPublicKey;
import freenet.crypt.RandomSource;
import freenet.crypt.SHA256;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.FreenetInetAddress;
import freenet.io.comm.Message;
import freenet.io.comm.MessageCore;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.Peer;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
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
import freenet.keys.KeyVerifyException;
import freenet.keys.NodeCHK;
import freenet.keys.NodeSSK;
import freenet.keys.SSKBlock;
import freenet.keys.SSKVerifyException;
import freenet.l10n.L10n;
import freenet.node.updater.NodeUpdateManager;
import freenet.node.useralerts.BuildOldAgeUserAlert;
import freenet.node.useralerts.ExtOldAgeUserAlert;
import freenet.node.useralerts.MeaningfulNodeNameUserAlert;
import freenet.node.useralerts.TimeSkewDetectedUserAlert;
import freenet.node.useralerts.UserAlert;
import freenet.pluginmanager.PluginManager;
import freenet.store.BerkeleyDBFreenetStore;
import freenet.store.FreenetStore;
import freenet.store.KeyCollisionException;
import freenet.support.DoubleTokenBucket;
import freenet.support.Fields;
import freenet.support.FileLoggerHook;
import freenet.support.HTMLEncoder;
import freenet.support.HTMLNode;
import freenet.support.HexUtil;
import freenet.support.ImmutableByteArrayWrapper;
import freenet.support.LRUHashtable;
import freenet.support.LRUQueue;
import freenet.support.Logger;
import freenet.support.ShortBuffer;
import freenet.support.SimpleFieldSet;
import freenet.support.api.BooleanCallback;
import freenet.support.api.IntCallback;
import freenet.support.api.LongCallback;
import freenet.support.api.ShortCallback;
import freenet.support.api.StringCallback;

/**
 * @author amphibian
 */
public class Node implements TimeSkewDetectorCallback {

	private static boolean logMINOR;
	
	private static MeaningfulNodeNameUserAlert nodeNameUserAlert;
	private static BuildOldAgeUserAlert buildOldAgeUserAlert;
	private static TimeSkewDetectedUserAlert timeSkewDetectedUserAlert;
	
	public class NodeNameCallback implements StringCallback{
			Node node;
		
			NodeNameCallback(Node n) {
				node=n;
			}
			public String get() {
				if(myName.startsWith("Node id|")|| myName.equals("MyFirstFreenetNode")){
					clientCore.alerts.register(nodeNameUserAlert);
				}else{
					clientCore.alerts.unregister(nodeNameUserAlert);
				}
				return myName;
			}

			public void set(String val) throws InvalidConfigValueException {
				myName = val;
				if(myName.startsWith("Node id|")|| myName.equals("MyFirstFreenetNode")){
					clientCore.alerts.register(nodeNameUserAlert);
				}else{
					clientCore.alerts.unregister(nodeNameUserAlert);
				}
			}
	}
	
	private class L10nCallback implements StringCallback, EnumerableOptionCallback{
		
		public String get() {
			return L10n.getSelectedLanguage();
		}
		
		public void set(String val) throws InvalidConfigValueException {
			if(get().equalsIgnoreCase(val)) return;
			try {
				L10n.setLanguage(val);
			} catch (MissingResourceException e) {
				throw new InvalidConfigValueException(e.getLocalizedMessage());
			}
		}
		
		public void setPossibleValues(String[] val) {
			throw new NullPointerException("Should not happen!");
		}
		
		public String[] getPossibleValues() {
			return L10n.AVAILABLE_LANGUAGES;
		}
	}
	
	/** Stats */
	public final NodeStats nodeStats;
	
	/** Config object for the whole node. */
	public final PersistentConfig config;
	
	// Static stuff related to logger
	
	/** Directory to log to */
	static File logDir;
	/** Maximum size of gzipped logfiles */
	static long maxLogSize;
	/** Log config handler */
	public static LoggingConfigHandler logConfigHandler;
	
	/** If true, local requests and inserts aren't cached.
	 * This opens up a glaring vulnerability; connected nodes
	 * can then probe the store, and if the node doesn't have the
	 * content, they know for sure that it was a local request.
	 * HOWEVER, if we don't do this, then a non-full seized 
	 * datastore will contain everything requested by the user...
	 * Also, remote probing is possible by peers, although it is
	 * more difficult, and leaves more plausible deniability,
	 * than the first attack.
	 * 
	 * So it may be useful on some darknets, and is useful for 
	 * debugging, but in general should be off on opennet and 
	 * most darknets.
	 */
	public static final boolean DONT_CACHE_LOCAL_REQUESTS = false;
	public static final int PACKETS_IN_BLOCK = 32;
	public static final int PACKET_SIZE = 1024;
	public static final double DECREMENT_AT_MIN_PROB = 0.25;
	public static final double DECREMENT_AT_MAX_PROB = 0.5;
	// Send keepalives every 2.5-5.0 seconds
	public static final int KEEPALIVE_INTERVAL = 2500;
	// If no activity for 30 seconds, node is dead
	public static final int MAX_PEER_INACTIVITY = 60000;
	/** Time after which a handshake is assumed to have failed. */
	public static final int HANDSHAKE_TIMEOUT = 5000;
	// Inter-handshake time must be at least 2x handshake timeout
	public static final int MIN_TIME_BETWEEN_HANDSHAKE_SENDS = HANDSHAKE_TIMEOUT*2; // 10-20 secs
	public static final int RANDOMIZED_TIME_BETWEEN_HANDSHAKE_SENDS = HANDSHAKE_TIMEOUT*2; // avoid overlap when the two handshakes are at the same time
	public static final int MIN_TIME_BETWEEN_VERSION_PROBES = HANDSHAKE_TIMEOUT*4;
	public static final int RANDOMIZED_TIME_BETWEEN_VERSION_PROBES = HANDSHAKE_TIMEOUT*2; // 20-30 secs
	public static final int MIN_TIME_BETWEEN_VERSION_SENDS = HANDSHAKE_TIMEOUT*4;
	public static final int RANDOMIZED_TIME_BETWEEN_VERSION_SENDS = HANDSHAKE_TIMEOUT*2; // 20-30 secs
	public static final int MIN_TIME_BETWEEN_BURSTING_HANDSHAKE_BURSTS = HANDSHAKE_TIMEOUT*24; // 2-5 minutes
	public static final int RANDOMIZED_TIME_BETWEEN_BURSTING_HANDSHAKE_BURSTS = HANDSHAKE_TIMEOUT*36;
	public static final int MIN_BURSTING_HANDSHAKE_BURST_SIZE = 1; // 1-4 handshake sends per burst
	public static final int RANDOMIZED_BURSTING_HANDSHAKE_BURST_SIZE = 3;
	// If we don't receive any packets at all in this period, from any node, tell the user
	public static final long ALARM_TIME = 60*1000;
	
	// 900ms
	static final int MIN_INTERVAL_BETWEEN_INCOMING_SWAP_REQUESTS = 900;
	public static final int SYMMETRIC_KEY_LENGTH = 32; // 256 bits - note that this isn't used everywhere to determine it
	/** Minimum space for zipped logfiles on testnet */
	static final long TESTNET_MIN_MAX_ZIPPED_LOGFILES = 512*1024*1024;
	static final String TESTNET_MIN_MAX_ZIPPED_LOGFILES_STRING = "512M";
	
	/** Datastore directory */
	private final File storeDir;

	/** The number of bytes per key total in all the different datastores. All the datastores
	 * are always the same size in number of keys. */
	static final int sizePerKey = CHKBlock.DATA_LENGTH + CHKBlock.TOTAL_HEADERS_LENGTH +
		DSAPublicKey.PADDED_SIZE + SSKBlock.DATA_LENGTH + SSKBlock.TOTAL_HEADERS_LENGTH;
	
	/** The maximum number of keys stored in each of the datastores, cache and store combined. */
	private long maxTotalKeys;
	private long maxCacheKeys;
	private long maxStoreKeys;
	/** The maximum size of the datastore. Kept to avoid rounding turning 5G into 5368698672 */
	private long maxTotalDatastoreSize;
	/** If true, store shrinks occur immediately even if they are over 10% of the store size. If false,
	 * we just set the storeSize and do an offline shrink on the next startup. Online shrinks do not 
	 * preserve the most recently used data so are not recommended. */
	private boolean storeForceBigShrinks;
	
	private StatsConfig statsConf;
	/* These are private because must be protected by synchronized(this) */
	private final Environment storeEnvironment;
	private final EnvironmentMutableConfig envMutableConfig;
	private final SemiOrderedShutdownHook storeShutdownHook;
	private long databaseMaxMemory;
	/** The CHK datastore. Long term storage; data should only be inserted here if
	 * this node is the closest location on the chain so far, and it is on an 
	 * insert (because inserts will always reach the most specialized node; if we
	 * allow requests to store here, then we get pollution by inserts for keys not
	 * close to our specialization). These conclusions derived from Oskar's simulations. */
	private final FreenetStore chkDatastore;
	/** The SSK datastore. See description for chkDatastore. */
	private final FreenetStore sskDatastore;
	/** The store of DSAPublicKeys (by hash). See description for chkDatastore. */
	private final FreenetStore pubKeyDatastore;
	/** The CHK datacache. Short term cache which stores everything that passes
	 * through this node. */
	private final FreenetStore chkDatacache;
	/** The SSK datacache. Short term cache which stores everything that passes
	 * through this node. */
	private final FreenetStore sskDatacache;
	/** The public key datacache (by hash). Short term cache which stores 
	 * everything that passes through this node. */
	private final FreenetStore pubKeyDatacache;
	/** RequestSender's currently running, by KeyHTLPair */
	private final HashMap requestSenders;
	/** RequestSender's currently transferring, by key */
	private final HashMap transferringRequestSenders;
	/** UIDs of RequestHandler's currently transferring */
	private final HashSet transferringRequestHandlers;
	/** CHKInsertSender's currently running, by KeyHTLPair */
	private final HashMap insertSenders;
	/** FetchContext for ARKs */
	public final FetchContext arkFetcherContext;
	
	/** IP detector */
	public final NodeIPDetector ipDetector;
	/** For debugging/testing, set this to true to stop the
	 * probabilistic decrement at the edges of the HTLs. */
	boolean disableProbabilisticHTLs;
	/** If true, disable all hang-check functionality */
	public boolean disableHangCheckers;
	
	/** HashSet of currently running request UIDs */
	private final HashSet runningUIDs;
	private final HashSet runningCHKGetUIDs;
	private final HashSet runningSSKGetUIDs;
	private final HashSet runningCHKPutUIDs;
	private final HashSet runningSSKPutUIDs;
	
	/** Semi-unique ID for swap requests. Used to identify us so that the
	 * topology can be reconstructed. */
	public long swapIdentifier;
	private String myName;
	final LocationManager lm;
	/** My peers */
	public final PeerManager peers;
	/** Directory to put node, peers, etc into */
	final File nodeDir;
	/** Directory to put extra peer data into */
	final File extraPeerDataDir;
	/** Strong RNG */
	public final RandomSource random;
	/** Weak but fast RNG */
	public final Random fastWeakRandom;
	/** The object which handles incoming messages and allows us to wait for them */
	final MessageCore usm;
	
	// Darknet stuff
	
	private NodeCrypto darknetCrypto;
	
	// Opennet stuff
	
	private final NodeCryptoConfig opennetCryptoConfig;
	private OpennetManager opennet;
	
	// General stuff
	
	public final PacketSender ps;
	final DNSRequester dnsr;
	final NodeDispatcher dispatcher;
	static final int MAX_MEMORY_CACHED_PUBKEYS = 1000;
	final LRUHashtable cachedPubKeys;
	final boolean testnetEnabled;
	final TestnetHandler testnetHandler;
	final StaticSwapRequestInterval swapInterval;
	public final DoubleTokenBucket outputThrottle;
	private int outputBandwidthLimit;
	private int inputBandwidthLimit;
	boolean inputLimitDefault;
	public static final short DEFAULT_MAX_HTL = (short)10;
	public static final int DEFAULT_SWAP_INTERVAL = 2000;
	private short maxHTL;
	/** Type identifier for fproxy node to node messages, as sent on DMT.nodeToNodeMessage's */
	public static final int N2N_MESSAGE_TYPE_FPROXY = 1;
	/** Identifier within fproxy messages for simple, short text messages to be displayed on the homepage as useralerts */
	public static final int N2N_TEXT_MESSAGE_TYPE_USERALERT = 1;
	/** Identifier within fproxy messages for an offer to transfer a file */
	public static final int N2N_TEXT_MESSAGE_TYPE_FILE_OFFER = 2;
	/** Identifier within fproxy messages for accepting an offer to transfer a file */
	public static final int N2N_TEXT_MESSAGE_TYPE_FILE_OFFER_ACCEPTED = 3;
	/** Identifier within fproxy messages for rejecting an offer to transfer a file */
	public static final int N2N_TEXT_MESSAGE_TYPE_FILE_OFFER_REJECTED = 4;
	public static final int EXTRA_PEER_DATA_TYPE_N2NTM = 1;
	public static final int EXTRA_PEER_DATA_TYPE_PEER_NOTE = 2;
	public static final int EXTRA_PEER_DATA_TYPE_QUEUED_TO_SEND_N2NTM = 3;
	public static final int PEER_NOTE_TYPE_PRIVATE_DARKNET_COMMENT = 1;
	
	public final long bootID;
	public final long startupTime;
	
	public final NodeClientCore clientCore;
	
	// The version we were before we restarted.
	public int lastVersion;
	
	/** NodeUpdater **/
	public final NodeUpdateManager nodeUpdater;
	
	// Things that's needed to keep track of
	public final PluginManager pluginManager;
	public freenet.oldplugins.plugin.PluginManager pluginManager2;
	
	// Helpers
	public final InetAddress localhostAddress;
	public final FreenetInetAddress fLocalhostAddress;

	private boolean wasTestnet;

	// The node starter
	private static NodeStarter nodeStarter;
	
	// The watchdog will be silenced until it's true
	private boolean hasStarted;
	private boolean isStopping = false;
	
	// Debugging stuff
	private static final boolean USE_RAM_PUBKEYS_CACHE = true;
	
	/**
	 * Read all storable settings (identity etc) from the node file.
	 * @param filename The name of the file to read from.
	 */
	private void readNodeFile(String filename, RandomSource r) throws IOException {
		// REDFLAG: Any way to share this code with NodePeer?
		FileInputStream fis = new FileInputStream(filename);
		InputStreamReader isr = new InputStreamReader(fis, "UTF-8");
		BufferedReader br = new BufferedReader(isr);
		SimpleFieldSet fs = new SimpleFieldSet(br, false, true);
		br.close();
		// Read contents
		String[] udp = fs.getAll("physical.udp");
		if((udp != null) && (udp.length > 0)) {
			for(int i=0;i<udp.length;i++) {
				// Just keep the first one with the correct port number.
				Peer p;
				try {
					p = new Peer(udp[i], false);
				} catch (PeerParseException e) {
					IOException e1 = new IOException();
					e1.initCause(e);
					throw e1;
				}
				if(p.getPort() == getDarknetPortNumber()) {
					// DNSRequester doesn't deal with our own node
					ipDetector.setOldIPAddress(p.getFreenetAddress());
					break;
				}
			}
		}
		
		darknetCrypto.readCrypto(fs);
		
		swapIdentifier = Fields.bytesToLong(darknetCrypto.identityHashHash);
		String loc = fs.get("location");
		Location l;
		try {
			l = new Location(loc);
		} catch (FSParseException e) {
			IOException e1 = new IOException();
			e1.initCause(e);
			throw e1;
		}
		lm.setLocation(l);
		myName = fs.get("myName");
		if(myName == null) {
			myName = newName();
		}
		
		String verString = fs.get("version");
		if(verString == null) {
			Logger.error(this, "No version!");
			System.err.println("No version!");
		} else {
			lastVersion = Version.getArbitraryBuildNumber(verString);
		}
		
		wasTestnet = Fields.stringToBool(fs.get("testnet"), false);
	}

	private String newName() {
		return "Node id|"+random.nextLong();
	}

	public void writeNodeFile() {
		writeNodeFile(new File(nodeDir, "node-"+getDarknetPortNumber()), new File(nodeDir, "node-"+getDarknetPortNumber()+".bak"));
	}
	
	private void writeNodeFile(File orig, File backup) {
		SimpleFieldSet fs = darknetCrypto.exportPrivateFieldSet();
		
		if(orig.exists()) backup.delete();
		
		FileOutputStream fos = null;
		try {
			fos = new FileOutputStream(backup);
			OutputStreamWriter osr = new OutputStreamWriter(fos, "UTF-8");
			BufferedWriter bw = new BufferedWriter(osr);
			fs.writeTo(bw);
			bw.close();
			if(!backup.renameTo(orig)) {
				orig.delete();
				if(!backup.renameTo(orig)) {
					Logger.error(this, "Could not rename new node file "+backup+" to "+orig);
				}
			}
		} catch (IOException e) {
			if(fos != null) {
				try {
					fos.close();
				} catch (IOException e1) {
					Logger.error(this, "Cannot close "+backup+": "+e1, e1);
				}
			}
		}
	}

	private void initNodeFileSettings(RandomSource r) {
		Logger.normal(this, "Creating new node file from scratch");
		// Don't need to set getDarknetPortNumber()
		// FIXME use a real IP!
		darknetCrypto.initCrypto();
		swapIdentifier = Fields.bytesToLong(darknetCrypto.identityHashHash);
		myName = newName();
	}

	/**
	 * Read the config file from the arguments.
	 * Then create a node.
	 * Anything that needs static init should ideally be in here.
	 */
	public static void main(String[] args) throws IOException {
		NodeStarter.main(args);
	}
	
	public boolean isUsingWrapper(){
		if(nodeStarter!=null && WrapperManager.isControlledByNativeWrapper())
			return true;
		else 
			return false;
	}
	
	public NodeStarter getNodeStarter(){
		return nodeStarter;
	}
	
	/**
	 * Create a Node from a Config object.
	 * @param config The Config object for this node.
	 * @param random The random number generator for this node. Passed in because we may want
	 * to use a non-secure RNG for e.g. one-JVM live-code simulations. Should be a Yarrow in
	 * a production node.
	 * @param the loggingHandler
	 * @throws NodeInitException If the node initialization fails.
	 */
	 Node(PersistentConfig config, RandomSource random, LoggingConfigHandler lc, NodeStarter ns) throws NodeInitException {
		// Easy stuff
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		String tmp = "Initializing Node using Freenet Build #"+Version.buildNumber()+" r"+Version.cvsRevision+" and freenet-ext Build #"+NodeStarter.extBuildNumber+" r"+NodeStarter.extRevisionNumber+" with "+System.getProperty("java.vm.vendor")+" JVM version "+System.getProperty("java.vm.version")+" running on "+System.getProperty("os.arch")+' '+System.getProperty("os.name")+' '+System.getProperty("os.version");
		Logger.normal(this, tmp);
		System.out.println(tmp);
	  	nodeStarter=ns;
		if(logConfigHandler != lc)
			logConfigHandler=lc;
		startupTime = System.currentTimeMillis();
		nodeNameUserAlert = new MeaningfulNodeNameUserAlert(this);
		recentlyCompletedIDs = new LRUQueue();
		this.config = config;
		this.random = random;
		byte buffer[] = new byte[16];
		random.nextBytes(buffer);
		this.fastWeakRandom = new MersenneTwister(buffer);
		cachedPubKeys = new LRUHashtable();
		lm = new LocationManager(random);

		try {
			localhostAddress = InetAddress.getByName("127.0.0.1");
		} catch (UnknownHostException e3) {
			// Does not do a reverse lookup, so this is impossible
			throw new Error(e3);
		}
		fLocalhostAddress = new FreenetInetAddress(localhostAddress);
		requestSenders = new HashMap();
		transferringRequestSenders = new HashMap();
		transferringRequestHandlers = new HashSet();
		insertSenders = new HashMap();
		runningUIDs = new HashSet();
		runningCHKGetUIDs = new HashSet();
		runningSSKGetUIDs = new HashSet();
		runningCHKPutUIDs = new HashSet();
		runningSSKPutUIDs = new HashSet();
		bootID = random.nextLong();
		
		buildOldAgeUserAlert = new BuildOldAgeUserAlert();

		int sortOrder = 0;
		// Setup node-specific configuration
		SubConfig nodeConfig = new SubConfig("node", config);
		
		nodeConfig.register("disableProbabilisticHTLs", false, sortOrder++, true, false, "Node.disablePHTLS", "Node.disablePHTLSLong", 
				new BooleanCallback() {

					public boolean get() {
						return disableProbabilisticHTLs;
					}

					public void set(boolean val) throws InvalidConfigValueException {
						disableProbabilisticHTLs = val;
					}
			
		});
		
		disableProbabilisticHTLs = nodeConfig.getBoolean("disableProbabilisticHTLs");
		
		nodeConfig.register("maxHTL", DEFAULT_MAX_HTL, sortOrder++, true, false, "Node.maxHTL", "Node.maxHTLLong", new ShortCallback() {

					public short get() {
						return maxHTL;
					}

					public void set(short val) throws InvalidConfigValueException {
						if(maxHTL < 0) throw new InvalidConfigValueException("Impossible max HTL");
						maxHTL = val;
					}
		});
		
		maxHTL = nodeConfig.getShort("maxHTL");
		
		// FIXME maybe these should persist? They need to be private.
		decrementAtMax = random.nextDouble() <= DECREMENT_AT_MAX_PROB;
		decrementAtMin = random.nextDouble() <= DECREMENT_AT_MIN_PROB;
		
		// Determine where to bind to
		
		usm = new MessageCore();
		
		// Determine the port number
		
		NodeCryptoConfig darknetConfig = new NodeCryptoConfig(nodeConfig, sortOrder++);
		sortOrder += NodeCryptoConfig.OPTION_COUNT;
		darknetCrypto = new NodeCrypto(sortOrder++, this, false, darknetConfig);

		// Must be created after darknetCrypto
		dnsr = new DNSRequester(this);
		ps = new PacketSender(this);
		// FIXME maybe these configs should actually be under a node.ip subconfig?
		ipDetector = new NodeIPDetector(this, darknetCrypto);
		sortOrder = ipDetector.registerConfigs(nodeConfig, sortOrder);
		
		Logger.normal(Node.class, "Creating node...");

		// Bandwidth limit

		nodeConfig.register("outputBandwidthLimit", "15K", sortOrder++, false, true, "Node.outBWLimit", "Node.outBWLimitLong", new IntCallback() {
					public int get() {
						//return BlockTransmitter.getHardBandwidthLimit();
						return outputBandwidthLimit;
					}
					public void set(int obwLimit) throws InvalidConfigValueException {
						if(obwLimit <= 0) throw new InvalidConfigValueException(l10n("bwlimitMustBePositive"));
						synchronized(Node.this) {
							outputBandwidthLimit = obwLimit;
						}
						outputThrottle.changeNanosAndBucketSizes((1000L * 1000L * 1000L) / obwLimit, obwLimit/2, (obwLimit * 2) / 5);
						nodeStats.setOutputLimit(obwLimit);
					}
		});
		
		int obwLimit = nodeConfig.getInt("outputBandwidthLimit");
		if(obwLimit <= 0)
			throw new NodeInitException(NodeInitException.EXIT_BAD_BWLIMIT, "Invalid outputBandwidthLimit");
		outputBandwidthLimit = obwLimit;
		outputThrottle = new DoubleTokenBucket(obwLimit/2, (1000L*1000L*1000L) /  obwLimit, obwLimit, (obwLimit * 2) / 5);
		
		nodeConfig.register("inputBandwidthLimit", "-1", sortOrder++, false, true, "Node.inBWLimit", "Node.inBWLimitLong",	new IntCallback() {
					public int get() {
						if(inputLimitDefault) return -1;
						return inputBandwidthLimit;
					}
					public void set(int ibwLimit) throws InvalidConfigValueException {
						synchronized(this) {
							inputBandwidthLimit = ibwLimit;
							if(ibwLimit == -1) {
								inputLimitDefault = true;
								ibwLimit = outputBandwidthLimit * 4;
							} else {
								if(ibwLimit <= 1) throw new InvalidConfigValueException(l10n("bandwidthLimitMustBePositiveOrMinusOne"));
								inputLimitDefault = false;
							}
						}
						nodeStats.setInputLimit(ibwLimit);
					}
		});
		
		int ibwLimit = nodeConfig.getInt("inputBandwidthLimit");
		if(obwLimit <= 0)
			throw new NodeInitException(NodeInitException.EXIT_BAD_BWLIMIT, "Invalid inputBandwidthLimit");
		inputBandwidthLimit = ibwLimit;
		if(ibwLimit == -1) {
			inputLimitDefault = true;
			ibwLimit = obwLimit * 4;
		}
		
		// SwapRequestInterval
		
		nodeConfig.register("swapRequestSendInterval", DEFAULT_SWAP_INTERVAL, sortOrder++, true, false,
				"Node.swapRInterval", "Node.swapRIntervalLong",
				new IntCallback() {
					public int get() {
						return swapInterval.fixedInterval;
					}
					public void set(int val) throws InvalidConfigValueException {
						swapInterval.set(val);
					}
		});
		
		swapInterval = new StaticSwapRequestInterval(nodeConfig.getInt("swapRequestSendInterval"));
		
		// Testnet.
		// Cannot be enabled/disabled on the fly.
		// If enabled, forces certain other config options.
		
		if((testnetHandler = TestnetHandler.maybeCreate(this, config)) != null) {
			String msg = "WARNING: ENABLING TESTNET CODE! This WILL seriously jeopardize your anonymity!";
			Logger.error(this, msg);
			System.err.println(msg);
			testnetEnabled = true;
			if(logConfigHandler.getFileLoggerHook() == null) {
				System.err.println("Forcing logging enabled (essential for testnet)");
				logConfigHandler.forceEnableLogging();
			}
			int x = Logger.globalGetThreshold();
			if(!((x == Logger.MINOR) || (x == Logger.DEBUG))) {
				System.err.println("Forcing log threshold to MINOR for testnet, was "+x);
				Logger.globalSetThreshold(Logger.MINOR);
			}
			if(logConfigHandler.getMaxZippedLogFiles() < TESTNET_MIN_MAX_ZIPPED_LOGFILES) {
				System.err.println("Forcing max zipped logfiles space to 256MB for testnet");
				try {
					logConfigHandler.setMaxZippedLogFiles(TESTNET_MIN_MAX_ZIPPED_LOGFILES_STRING);
				} catch (InvalidConfigValueException e) {
					throw new Error("Impossible: "+e);
				}
			}
		} else {
			String s = "Testnet mode DISABLED. You may have some level of anonymity. :)\n"+
				"Note that this version of Freenet is still a very early alpha, and may well have numerous bugs and design flaws.\n"+
				"In particular: YOU ARE WIDE OPEN TO YOUR IMMEDIATE DARKNET PEERS! They can eavesdrop on your requests with relatively little difficulty at present (correlation attacks etc).";
			Logger.normal(this, s);
			System.err.println(s);
			testnetEnabled = false;
			if(wasTestnet) {
				FileLoggerHook flh = logConfigHandler.getFileLoggerHook();
				if(flh != null) flh.deleteAllOldLogFiles();
			}
		}
		
		// Directory for node-related files other than store
		
		nodeConfig.register("nodeDir", ".", sortOrder++, true, false, "Node.nodeDir", "Node.nodeDirLong", 
				new StringCallback() {
					public String get() {
						return nodeDir.getPath();
					}
					public void set(String val) throws InvalidConfigValueException {
						if(nodeDir.equals(new File(val))) return;
						// FIXME support it
						// Don't translate the below as very few users will use it.
						throw new InvalidConfigValueException("Moving node directory on the fly not supported at present");
					}
		});
		
		nodeDir = new File(nodeConfig.getString("nodeDir"));
		if(!((nodeDir.exists() && nodeDir.isDirectory()) || (nodeDir.mkdir()))) {
			String msg = "Could not find or create datastore directory";
			throw new NodeInitException(NodeInitException.EXIT_BAD_NODE_DIR, msg);
		}

		// After we have set up testnet and IP address, load the node file
		try {
			// FIXME should take file directly?
			readNodeFile(new File(nodeDir, "node-"+getDarknetPortNumber()).getPath(), random);
		} catch (IOException e) {
			try {
				readNodeFile(new File("node-"+getDarknetPortNumber()+".bak").getPath(), random);
			} catch (IOException e1) {
				initNodeFileSettings(random);
			}
		}

		if(wasTestnet != testnetEnabled) {
			Logger.error(this, "Switched from testnet mode to non-testnet mode or vice versa! Regenerating pubkey, privkey, and deleting logs.");
			// FIXME do we delete logs?
			darknetCrypto.initCrypto();
		}

		usm.setDispatcher(dispatcher=new NodeDispatcher(this));
		
		// Then read the peers
		peers = new PeerManager(this);
		peers.tryReadPeers(new File(nodeDir, "peers-"+getDarknetPortNumber()).getPath(), darknetCrypto, false);
		peers.writePeers();
		peers.updatePMUserAlert();

		// Opennet
		
		final SubConfig opennetConfig = new SubConfig("node.opennet", config);
		
		// Can be enabled on the fly
		opennetConfig.register("enabled", false, 0, false, true, "Node.opennetEnabled", "Node.opennetEnabledLong", new BooleanCallback() {
			public boolean get() {
				synchronized(Node.this) {
					return opennet != null;
				}
			}
			public void set(boolean val) throws InvalidConfigValueException {
				synchronized(Node.this) {
					if(val == (opennet != null)) return;
					if(val) {
						try {
							opennet = new OpennetManager(Node.this, opennetCryptoConfig);
						} catch (NodeInitException e) {
							throw new InvalidConfigValueException(e.getMessage());
						}
					} else {
						opennet = null;
					}
				}
				if(val) opennet.start();
				else opennet.stop();
			}
		});
		
		boolean opennetEnabled = opennetConfig.getBoolean("enabled");
		
		opennetCryptoConfig = new NodeCryptoConfig(opennetConfig, 1 /* 0 = enabled */);
		
		if(opennetEnabled) {
			opennet = new OpennetManager(this, opennetCryptoConfig);
			// Will be started later
		} else {
			opennet = null;
		}
		
		opennetConfig.finishedInitialization();
		
		// Extra Peer Data Directory
		nodeConfig.register("extraPeerDataDir", new File(nodeDir, "extra-peer-data-"+getDarknetPortNumber()).toString(), sortOrder++, true, false, "Node.extraPeerDir", "Node.extraPeerDirLong",
				new StringCallback() {
					public String get() {
						return extraPeerDataDir.getPath();
					}
					public void set(String val) throws InvalidConfigValueException {
						if(extraPeerDataDir.equals(new File(val))) return;
						// FIXME
						throw new InvalidConfigValueException("Moving extra peer data directory on the fly not supported at present");
					}
		});
		extraPeerDataDir = new File(nodeConfig.getString("extraPeerDataDir"));
		if(!((extraPeerDataDir.exists() && extraPeerDataDir.isDirectory()) || (extraPeerDataDir.mkdir()))) {
			String msg = "Could not find or create extra peer data directory";
			throw new NodeInitException(NodeInitException.EXIT_EXTRA_PEER_DATA_DIR, msg);
		}
		
		// Name 	 
		nodeConfig.register("name", myName, sortOrder++, false, true, "Node.nodeName", "Node.nodeNameLong", 	 
						new NodeNameCallback(this)); 	 
		myName = nodeConfig.getString("name"); 	 

		// Datastore
		
		nodeConfig.register("storeForceBigShrinks", false, sortOrder++, true, false, "Node.forceBigShrink", "Node.forceBigShrinkLong",
				new BooleanCallback() {

					public boolean get() {
						synchronized(Node.this) {
							return storeForceBigShrinks;
						}
					}

					public void set(boolean val) throws InvalidConfigValueException {
						synchronized(Node.this) {
							storeForceBigShrinks = val;
						}
					}
			
		});
		
		nodeConfig.register("storeSize", "1G", sortOrder++, false, true, "Node.storeSize", "Node.storeSizeLong", 
				new LongCallback() {

					public long get() {
						return maxTotalDatastoreSize;
					}

					public void set(long storeSize) throws InvalidConfigValueException {
						if((storeSize < 0) || (storeSize < (32 * 1024 * 1024)))
							throw new InvalidConfigValueException(l10n("invalidStoreSize"));
						long newMaxStoreKeys = storeSize / sizePerKey;
						if(newMaxStoreKeys == maxTotalKeys) return;
						// Update each datastore
						synchronized(Node.this) {
							maxTotalDatastoreSize = storeSize;
							maxTotalKeys = newMaxStoreKeys;
							maxStoreKeys = maxTotalKeys / 2;
							maxCacheKeys = maxTotalKeys - maxStoreKeys;
						}
						try {
							chkDatastore.setMaxKeys(maxStoreKeys, storeForceBigShrinks);
							chkDatacache.setMaxKeys(maxCacheKeys, storeForceBigShrinks);
							pubKeyDatastore.setMaxKeys(maxStoreKeys, storeForceBigShrinks);
							pubKeyDatacache.setMaxKeys(maxCacheKeys, storeForceBigShrinks);
							sskDatastore.setMaxKeys(maxStoreKeys, storeForceBigShrinks);
							sskDatacache.setMaxKeys(maxCacheKeys, storeForceBigShrinks);
						} catch (IOException e) {
							// FIXME we need to be able to tell the user.
							Logger.error(this, "Caught "+e+" resizing the datastore", e);
							System.err.println("Caught "+e+" resizing the datastore");
							e.printStackTrace();
						} catch (DatabaseException e) {
							Logger.error(this, "Caught "+e+" resizing the datastore", e);
							System.err.println("Caught "+e+" resizing the datastore");
							e.printStackTrace();
						}
					}
		});
		
		maxTotalDatastoreSize = nodeConfig.getLong("storeSize");
		
		if(maxTotalDatastoreSize < 0 || maxTotalDatastoreSize < (32 * 1024 * 1024)) { // totally arbitrary minimum!
			throw new NodeInitException(NodeInitException.EXIT_INVALID_STORE_SIZE, "Invalid store size");
		}

		maxTotalKeys = maxTotalDatastoreSize / sizePerKey;
		
		nodeConfig.register("storeDir", ".", sortOrder++, true, true, "Node.storeDirectory", "Node.storeDirectoryLong", 
				new StringCallback() {
					public String get() {
						return storeDir.getPath();
					}
					public void set(String val) throws InvalidConfigValueException {
						if(storeDir.equals(new File(val))) return;
						// FIXME
						throw new InvalidConfigValueException("Moving datastore on the fly not supported at present");
					}
		});
		
		storeDir = new File(nodeConfig.getString("storeDir"));
		if(!((storeDir.exists() && storeDir.isDirectory()) || (storeDir.mkdir()))) {
			String msg = "Could not find or create datastore directory";
			throw new NodeInitException(NodeInitException.EXIT_STORE_OTHER, msg);
		}

		maxStoreKeys = maxTotalKeys / 2;
		maxCacheKeys = maxTotalKeys - maxStoreKeys;
		
		// Setup datastores
		
		// First, global settings
		
		// Percentage of the database that must contain usefull data
		// decrease to increase performance, increase to save disk space
		System.setProperty("je.cleaner.minUtilization","90");
		// Delete empty log files
		System.setProperty("je.cleaner.expunge","true");
		EnvironmentConfig envConfig = new EnvironmentConfig();
		envConfig.setAllowCreate(true);
		envConfig.setTransactional(true);
		envConfig.setTxnWriteNoSync(true);
		envConfig.setLockTimeout(600*1000*1000); // should be long enough even for severely overloaded nodes!
		// Note that the above is in *MICRO*seconds.
		
		File dbDir = new File(storeDir, "database-"+getDarknetPortNumber());
		dbDir.mkdirs();
		
		File reconstructFile = new File(dbDir, "reconstruct");
		
		Environment env = null;
		EnvironmentMutableConfig mutableConfig;
		
		boolean tryDbLoad = false;
		
		String suffix = "-" + getDarknetPortNumber();
		
		// This can take some time
		System.out.println("Starting database...");
		try {
			if(reconstructFile.exists()) {
				reconstructFile.delete();
				throw new DatabaseException();
			}
			env = new Environment(dbDir, envConfig);
			mutableConfig = env.getConfig();
		} catch (DatabaseException e) {

			// Close the database
			if(env != null) {
				try {
					env.close();
				} catch (Throwable t) {
					System.err.println("Error closing database: "+t+" after "+e);
					t.printStackTrace();
				}
			}
			
			// First try DbDump
			
			System.err.println("Attempting DbDump-level recovery...");
			
			boolean[] isStores = new boolean[] { true, false, true, false, true, false };
			short[] types = new short[] { 
					BerkeleyDBFreenetStore.TYPE_CHK,
					BerkeleyDBFreenetStore.TYPE_CHK,
					BerkeleyDBFreenetStore.TYPE_PUBKEY,
					BerkeleyDBFreenetStore.TYPE_PUBKEY,
					BerkeleyDBFreenetStore.TYPE_SSK,
					BerkeleyDBFreenetStore.TYPE_SSK
			};
			int[] lengths = new int[] {
					CHKBlock.TOTAL_HEADERS_LENGTH + CHKBlock.DATA_LENGTH,
					CHKBlock.TOTAL_HEADERS_LENGTH + CHKBlock.DATA_LENGTH,
					DSAPublicKey.PADDED_SIZE,
					DSAPublicKey.PADDED_SIZE,
					SSKBlock.TOTAL_HEADERS_LENGTH + SSKBlock.DATA_LENGTH,
					SSKBlock.TOTAL_HEADERS_LENGTH + SSKBlock.DATA_LENGTH
			};
			
			for(int i=0;i<types.length;i++) {
				boolean isStore = isStores[i];
				short type = types[i];
				String dbName = BerkeleyDBFreenetStore.getName(isStore, type);
				File dbFile = BerkeleyDBFreenetStore.getFile(isStore, type, storeDir, suffix);
				long keyCount = dbFile.length() / lengths[i];
				// This is *slow* :(
				int millis = (int)Math.min(24*60*60*1000 /* horrible hack, because of the wrapper's braindead timeout additions */, 
						5*60*1000 + (Math.max(keyCount, 1) * 10000));
				WrapperManager.signalStarting(millis);
				try {
					File target = new File(storeDir, dbName+".dump");
					System.err.println("Dumping "+dbName+" to "+target+" ("+keyCount+" keys from file, allowing "+millis+"ms)");
					DbDump.main(new String[] { "-r", "-h", dbDir.toString(), 
							"-s", dbName, "-f", target.toString() });
					tryDbLoad = true;
				} catch (DatabaseException e2) {
					System.err.println("DbDump recovery failed for "+dbName+" : "+e2);
					e2.printStackTrace();
				} catch (IOException e2) {
					System.err.println("DbDump recovery failed for "+dbName+" : "+e2);
					e2.printStackTrace();
				}
			}
			
			// Delete the database logs
			
			System.err.println("Deleting old database log files...");
			
			File[] files = dbDir.listFiles();
			for(int i=0;i<files.length;i++) {
				String name = files[i].getName().toLowerCase();
				if(name.endsWith(".jdb") || name.equals("je.lck"))
					if(!files[i].delete())
						System.err.println("Failed to delete old database log file "+files[i]);
			}
			
			System.err.println("Recovering...");
			// The database is broken
			// We will have to recover from scratch
			try {
				env = new Environment(dbDir, envConfig);
				mutableConfig = env.getConfig();
			} catch (DatabaseException e1) {
				System.err.println("Could not open store: "+e1);
				e1.printStackTrace();
				System.err.println("Previous error was (tried deleting database and retrying): "+e);
				e.printStackTrace();
				throw new NodeInitException(NodeInitException.EXIT_STORE_OTHER, e1.getMessage());
			}
		}
		storeEnvironment = env;
		envMutableConfig = mutableConfig;
		
		statsConf = new StatsConfig();
		statsConf.setClear(true);

		storeShutdownHook = new SemiOrderedShutdownHook();
		Runtime.getRuntime().addShutdownHook(storeShutdownHook);
		
		storeShutdownHook.addLateJob(new Thread() {
			public void run() {
				try {
					storeEnvironment.close();
					System.err.println("Successfully closed all datastores.");
				} catch (Throwable t) {
					System.err.println("Caught "+t+" closing environment");
					t.printStackTrace();
				}
			}
		});
		
		nodeConfig.register("databaseMaxMemory", "20M", sortOrder++, true, false, "Node.databaseMemory", "Node.databaseMemoryLong", 
				new LongCallback() {

			public long get() {
				return databaseMaxMemory;
			}

			public void set(long val) throws InvalidConfigValueException {
				if(val < 0)
					throw new InvalidConfigValueException(l10n("mustBePositive"));
				else if(val > (80 * Runtime.getRuntime().maxMemory() / 100))
					throw new InvalidConfigValueException(l10n("storeMaxMemTooHigh"));
				envMutableConfig.setCacheSize(val);
				try{
					storeEnvironment.setMutableConfig(envMutableConfig);
				} catch (DatabaseException e) {
					throw new InvalidConfigValueException(l10n("errorApplyingConfig", "error", e.getLocalizedMessage()));
				}
				databaseMaxMemory = val;
			}
			
		});

		databaseMaxMemory = nodeConfig.getLong("databaseMaxMemory");
		// see #1202
		if(databaseMaxMemory > (80 * Runtime.getRuntime().maxMemory() / 100)){
			Logger.error(this, "The databaseMemory setting is set too high " + databaseMaxMemory +
					" ... let's assume it's not what the user wants to do and restore the default.");
			databaseMaxMemory = Long.valueOf(((LongOption) nodeConfig.getOption("databaseMaxMemory")).getDefault()).longValue();
		}
		envMutableConfig.setCacheSize(databaseMaxMemory);
		// http://www.oracle.com/technology/products/berkeley-db/faq/je_faq.html#35
		// FIXME is this the correct place to set these parameters?
		envMutableConfig.setConfigParam("je.evictor.lruOnly", "false");
		envMutableConfig.setConfigParam("je.evictor.nodesPerScan", "100");
		
		try {
			storeEnvironment.setMutableConfig(envMutableConfig);
		} catch (DatabaseException e) {
			System.err.println("Could not set the database configuration: "+e);
			e.printStackTrace();
			throw new NodeInitException(NodeInitException.EXIT_STORE_OTHER, e.getMessage());			
		}
		
		try {
			Logger.normal(this, "Initializing CHK Datastore");
			System.out.println("Initializing CHK Datastore ("+maxStoreKeys+" keys)");
			chkDatastore = BerkeleyDBFreenetStore.construct(lastVersion, storeDir, true, suffix, maxStoreKeys, 
					CHKBlock.DATA_LENGTH, CHKBlock.TOTAL_HEADERS_LENGTH, true, BerkeleyDBFreenetStore.TYPE_CHK, storeEnvironment, random, storeShutdownHook, tryDbLoad, reconstructFile);
			Logger.normal(this, "Initializing CHK Datacache");
			System.out.println("Initializing CHK Datacache ("+maxCacheKeys+ ':' +maxCacheKeys+" keys)");
			chkDatacache = BerkeleyDBFreenetStore.construct(lastVersion, storeDir, false, suffix, maxCacheKeys, 
					CHKBlock.DATA_LENGTH, CHKBlock.TOTAL_HEADERS_LENGTH, true, BerkeleyDBFreenetStore.TYPE_CHK, storeEnvironment, random, storeShutdownHook, tryDbLoad, reconstructFile);
			Logger.normal(this, "Initializing pubKey Datastore");
			System.out.println("Initializing pubKey Datastore");
			pubKeyDatastore = BerkeleyDBFreenetStore.construct(lastVersion, storeDir, true, suffix, maxStoreKeys, 
					DSAPublicKey.PADDED_SIZE, 0, true, BerkeleyDBFreenetStore.TYPE_PUBKEY, storeEnvironment, random, storeShutdownHook, tryDbLoad, reconstructFile);
			Logger.normal(this, "Initializing pubKey Datacache");
			System.out.println("Initializing pubKey Datacache ("+maxCacheKeys+" keys)");
			pubKeyDatacache = BerkeleyDBFreenetStore.construct(lastVersion, storeDir, false, suffix, maxCacheKeys, 
					DSAPublicKey.PADDED_SIZE, 0, true, BerkeleyDBFreenetStore.TYPE_PUBKEY, storeEnvironment, random, storeShutdownHook, tryDbLoad, reconstructFile);
			// FIXME can't auto-fix SSK stores.
			Logger.normal(this, "Initializing SSK Datastore");
			System.out.println("Initializing SSK Datastore");
			sskDatastore = BerkeleyDBFreenetStore.construct(lastVersion, storeDir, true, suffix, maxStoreKeys, 
					SSKBlock.DATA_LENGTH, SSKBlock.TOTAL_HEADERS_LENGTH, false, BerkeleyDBFreenetStore.TYPE_SSK, storeEnvironment, random, storeShutdownHook, tryDbLoad, reconstructFile);
			Logger.normal(this, "Initializing SSK Datacache");
			System.out.println("Initializing SSK Datacache ("+maxCacheKeys+" keys)");
			sskDatacache = BerkeleyDBFreenetStore.construct(lastVersion, storeDir, false, suffix, maxStoreKeys, 
					SSKBlock.DATA_LENGTH, SSKBlock.TOTAL_HEADERS_LENGTH, false, BerkeleyDBFreenetStore.TYPE_SSK, storeEnvironment, random, storeShutdownHook, tryDbLoad, reconstructFile);
		} catch (FileNotFoundException e1) {
			String msg = "Could not open datastore: "+e1;
			Logger.error(this, msg, e1);
			System.err.println(msg);
			throw new NodeInitException(NodeInitException.EXIT_STORE_FILE_NOT_FOUND, msg);
		} catch (IOException e1) {
			String msg = "Could not open datastore: "+e1;
			Logger.error(this, msg, e1);
			System.err.println(msg);
			e1.printStackTrace();
			throw new NodeInitException(NodeInitException.EXIT_STORE_IOEXCEPTION, msg);
		} catch (DatabaseException e1) {
			try {
				reconstructFile.createNewFile();
			} catch (IOException e) {
				System.err.println("Cannot create reconstruct file "+reconstructFile+" : "+e+" - store will not be reconstructed !!!!");
				e.printStackTrace();
			}
			String msg = "Could not open datastore due to corruption, will attempt to reconstruct on next startup: "+e1;
			Logger.error(this, msg, e1);
			System.err.println(msg);
			e1.printStackTrace();
			throw new NodeInitException(NodeInitException.EXIT_STORE_RECONSTRUCT, msg);
		}

		// FIXME back compatibility
		SimpleFieldSet oldThrottleFS = null;
		File oldThrottle = new File("throttle.dat");
		String oldThrottleName = nodeConfig.getRawOption("throttleFile");
		if(oldThrottleName != null)
			oldThrottle = new File(oldThrottleName);
		if(oldThrottle.exists() && (!new File("node-throttle.dat").exists()) && lastVersion < 1021) {
			// Migrate from old throttle file to new node- and client- throttle files
			try {
				oldThrottleFS = SimpleFieldSet.readFrom(new File("throttle.dat"), false, true);
			} catch (IOException e) {
				// Ignore
			}
			oldThrottle.delete();
		}
		
		nodeStats = new NodeStats(this, sortOrder, new SubConfig("node.load", config), oldThrottleFS, obwLimit, ibwLimit);
		
		clientCore = new NodeClientCore(this, config, nodeConfig, nodeDir, getDarknetPortNumber(), sortOrder, oldThrottleFS == null ? null : oldThrottleFS.subset("RequestStarters"));

		nodeConfig.register("disableHangCheckers", false, sortOrder++, true, false, "Node.disableHangCheckers", "Node.disableHangCheckersLong", new BooleanCallback() {

			public boolean get() {
				return disableHangCheckers;
			}

			public void set(boolean val) throws InvalidConfigValueException {
				disableHangCheckers = val;
			}
			
		});
		
		disableHangCheckers = nodeConfig.getBoolean("disableHangCheckers");
		
		// l10n stuffs
		nodeConfig.register("l10n", Locale.getDefault().getLanguage().toLowerCase(), sortOrder++, false, true, 
				"Node.l10nLanguage",
				"Node.l10nLanguageLong",
				new L10nCallback());
		
		try {
			L10n.setLanguage(nodeConfig.getString("l10n"));
		} catch (MissingResourceException e) {
			try {
				L10n.setLanguage(nodeConfig.getOption("l10n").getDefault());
			} catch (MissingResourceException e1) {
				L10n.setLanguage(L10n.FALLBACK_DEFAULT);
			}
		}
		
		nodeConfig.finishedInitialization();
		writeNodeFile();
		
		// Initialize the plugin manager
		Logger.normal(this, "Initializing Plugin Manager");
		System.out.println("Initializing Plugin Manager");
		pluginManager = new PluginManager(this);
		pluginManager2 = new freenet.oldplugins.plugin.PluginManager(this);
		
		FetchContext ctx = clientCore.makeClient((short)0, true).getFetchContext();
		
		ctx.allowSplitfiles = false;
		ctx.dontEnterImplicitArchives = true;
		ctx.maxArchiveRestarts = 0;
		ctx.maxMetadataSize = 256;
		ctx.maxNonSplitfileRetries = 10;
		ctx.maxOutputLength = 4096;
		ctx.maxRecursionLevel = 2;
		ctx.maxTempLength = 4096;
		
		this.arkFetcherContext = ctx;
		
		// Node updater support
		
		try {
			nodeUpdater = NodeUpdateManager.maybeCreate(this, config);
		} catch (InvalidConfigValueException e) {
			e.printStackTrace();
			throw new NodeInitException(NodeInitException.EXIT_COULD_NOT_START_UPDATER, "Could not create Updater: "+e);
		}
		
		Logger.normal(this, "Node constructor completed");
		System.out.println("Node constructor completed");
	}
	
	public void start(boolean noSwaps) throws NodeInitException {
		
		if(!noSwaps)
			lm.startSender(this, this.swapInterval);
		dispatcher.start(nodeStats); // must be before usm
		dnsr.start();
		ps.start(nodeStats);
		peers.start(); // must be before usm
		nodeStats.start();
		
		usm.start(ps);
		darknetCrypto.start(disableHangCheckers);
		if(opennet != null)
			opennet.start();
		
		if(isUsingWrapper()) {
			Logger.normal(this, "Using wrapper correctly: "+nodeStarter);
			System.out.println("Using wrapper correctly: "+nodeStarter);
		} else {
			Logger.error(this, "NOT using wrapper (at least not correctly).  Your freenet-ext.jar <http://downloads.freenetproject.org/alpha/freenet-ext.jar> and/or wrapper.conf <https://emu.freenetproject.org/svn/trunk/apps/installer/installclasspath/config/wrapper.conf> need to be updated.");
			System.out.println("NOT using wrapper (at least not correctly).  Your freenet-ext.jar <http://downloads.freenetproject.org/alpha/freenet-ext.jar> and/or wrapper.conf <https://emu.freenetproject.org/svn/trunk/apps/installer/installclasspath/config/wrapper.conf> need to be updated.");
		}
		Logger.normal(this, "Freenet 0.7 Build #"+Version.buildNumber()+" r"+Version.cvsRevision);
		System.out.println("Freenet 0.7 Build #"+Version.buildNumber()+" r"+Version.cvsRevision);
		Logger.normal(this, "FNP port is on "+darknetCrypto.bindto+ ':' +getDarknetPortNumber());
		System.out.println("FNP port is on "+darknetCrypto.bindto+ ':' +getDarknetPortNumber());
		// Start services
		
//		SubConfig pluginManagerConfig = new SubConfig("pluginmanager3", config);
//		pluginManager3 = new freenet.plugin_new.PluginManager(pluginManagerConfig);
		
		ipDetector.start();

		// Node Updater
		try{
			Logger.normal(this, "Starting the node updater");
			nodeUpdater.start();
		}catch (Exception e) {
			e.printStackTrace();
			throw new NodeInitException(NodeInitException.EXIT_COULD_NOT_START_UPDATER, "Could not start Updater: "+e);
		}
		
		// Start testnet handler
		if(testnetHandler != null)
			testnetHandler.start();
		
		checkForEvilJVMBug();
		
		this.nodeStats.start();
		
		// TODO: implement a "required" version if needed
		if(!nodeUpdater.isEnabled() && (NodeStarter.RECOMMENDED_EXT_BUILD_NUMBER > NodeStarter.extBuildNumber))
			clientCore.alerts.register(new ExtOldAgeUserAlert());
		else if(NodeStarter.extBuildNumber == -1)
			clientCore.alerts.register(new ExtOldAgeUserAlert());
		
		this.clientCore.start(config);
		
		// After everything has been created, write the config file back to disk.
		if(config instanceof FreenetFilePersistentConfig) {
			FreenetFilePersistentConfig cfg = (FreenetFilePersistentConfig) config;
			cfg.finishedInit(this.ps);
			cfg.setHasNodeStarted();
		}
		config.store();
		
		// Process any data in the extra peer data directory
		peers.readExtraPeerData();
		
		Logger.normal(this, "Started node");
		
		hasStarted = true;
	}
	
	private void checkForEvilJVMBug() {
		// Now check whether we are likely to get the EvilJVMBug.
		// If we are running a Sun or Blackdown JVM, on Linux, and LD_ASSUME_KERNEL is not set, then we are.
		
		String jvmVendor = System.getProperty("java.vm.vendor");
		String jvmVersion = System.getProperty("java.vm.version");
		String osName = System.getProperty("os.name");
		String osVersion = System.getProperty("os.version");
		
		if(logMINOR) Logger.minor(this, "JVM vendor: "+jvmVendor+", JVM version: "+jvmVersion+", OS name: "+osName+", OS version: "+osVersion);
		
		if(jvmVendor.startsWith("Sun ")) {
			// Sun bugs
			
			// Spurious OOMs
			// http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=4855795
			// http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=2138757
			// http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=2138759
			// Fixed in 1.5.0_10 and 1.4.2_13
			
			boolean is142 = jvmVersion.startsWith("1.4.2_");
			boolean is150 = jvmVersion.startsWith("1.5.0_");
			
			boolean spuriousOOMs = false;
			
			if(is142 || is150) {
				String[] split = jvmVersion.split("_");
				String secondPart = split[1];
				if(secondPart.indexOf("-") != -1) {
					split = secondPart.split("-");
					secondPart = split[0];
				}
				int subver = Integer.parseInt(secondPart);
				
				Logger.minor(this, "JVM version: "+jvmVersion+" subver: "+subver+" from "+secondPart+" is142="+is142+" is150="+is150);
				
				if(is142) {
					if(subver < 13)
						spuriousOOMs = true;
				} else /*if(is150)*/ {
					if(subver < 10)
						spuriousOOMs = true;
				}
			}
			
			if(spuriousOOMs) {
				System.err.println("Please upgrade to at least sun jvm 1.4.2_13, 1.5.0_10 or 1.6 (recommended). This version is buggy and may cause spurious OutOfMemoryErrors.");
				clientCore.alerts.register(new UserAlert() {

					public String dismissButtonText() {
						// Not dismissable
						return null;
					}

					public HTMLNode getHTMLText() {
						HTMLNode n = new HTMLNode("div");
						L10n.addL10nSubstitution(n, "Node.buggyJVMWithLink", 
								new String[] { "link", "/link", "version" },
								new String[] { "<a href=\"/?_CHECKED_HTTP_=http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=4855795\">", 
								"</a>", HTMLEncoder.encode(System.getProperty("java.vm.version")) });
						return n;
					}

					public short getPriorityClass() {
						return UserAlert.ERROR;
					}

					public String getText() {
						return l10n("buggyJVM", "version", System.getProperty("java.vm.version"));
					}

					public String getTitle() {
						return l10n("buggyJVMTitle");
					}

					public boolean isValid() {
						return true;
					}

					public void isValid(boolean validity) {
						// Ignore
					}

					public void onDismiss() {
						// Ignore
					}

					public boolean shouldUnregisterOnDismiss() {
						return false;
					}

					public boolean userCanDismiss() {
						// Cannot be dismissed
						return false;
					}
					
				});
			}
			
			// If we are using the wrapper, we ignore:
			// Any problem should be detected by the watchdog and the node will be restarted
			// FIXME we should only check this on x86 (x86-64 doesn't have pthreads)
			// FIXME why only if not running the wrapper? It's worse with the wrapper of course... but if that's
			// the issue we should tell the user.
			if(osName.equals("Linux") && jvmVendor.startsWith("Sun ") && 
					((osVersion.indexOf("nptl")!=-1) || osVersion.startsWith("2.6") || 
							osVersion.startsWith("2.7") || osVersion.startsWith("3."))
							&& !isUsingWrapper()) {
				// Hopefully we won't still have to deal with this **** when THAT comes out! 
				// Check the environment.
				String assumeKernel;
				try {
					// It is essential to check the environment.
					// Make an alternative way to do it if you like.
					assumeKernel = System.getenv("LD_ASSUME_KERNEL");
				} catch (Error e) {
					assumeKernel = null;
					assumeKernel = WrapperManager.getProperties().getProperty("set.LD_ASSUME_KERNEL");
				}
				if((assumeKernel == null) || (assumeKernel.length() == 0) || (!(assumeKernel.startsWith("2.2") || assumeKernel.startsWith("2.4")))) {
					System.err.println(l10n("deadlockWarning"));
					Logger.error(this, l10n("deadlockWarning"));
					clientCore.alerts.register(new UserAlert() {
						
						public boolean userCanDismiss() {
							return false;
						}
						
						public String getTitle() {
							return l10n("deadlockTitle");
						}
						
						public String getText() {
							return l10n("deadlockWarning");
						}
						
						public HTMLNode getHTMLText() {
							return new HTMLNode("div", l10n("deadlockWarning"));
						}
						
						public short getPriorityClass() {
							return UserAlert.CRITICAL_ERROR;
						}
						
						public boolean isValid() {
							return true;
						}
						
						public void isValid(boolean validity) {
							// Not clearable.
						}
						
						public String dismissButtonText() {
							// Not dismissable.
							return null;
						}
						
						public boolean shouldUnregisterOnDismiss() {
							// Not dismissable.
							return false;
						}
						
						public void onDismiss() {
							// Not dismissable.
						}
					});
				}
			}
		}
		
	}

	private String l10n(String key) {
		return L10n.getString("Node."+key);
	}

	private String l10n(String key, String pattern, String value) {
		return L10n.getString("Node."+key, pattern, value);
	}

	/**
	 * Export volatile data about the node as a SimpleFieldSet
	 */
	public SimpleFieldSet exportVolatileFieldSet() {
		return nodeStats.exportVolatileFieldSet();
	}

	/**
	 * Do a routed ping of another node on the network by its location.
	 * @param loc2 The location of the other node to ping. It must match
	 * exactly.
	 * @return The number of hops it took to find the node, if it was found.
	 * Otherwise -1.
	 */
	public int routedPing(double loc2) {
		long uid = random.nextLong();
		int initialX = random.nextInt();
		Message m = DMT.createFNPRoutedPing(uid, loc2, maxHTL, initialX);
		Logger.normal(this, "Message: "+m);
		
		dispatcher.handleRouted(m);
		// FIXME: might be rejected
		MessageFilter mf1 = MessageFilter.create().setField(DMT.UID, uid).setType(DMT.FNPRoutedPong).setTimeout(5000);
		try {
			//MessageFilter mf2 = MessageFilter.create().setField(DMT.UID, uid).setType(DMT.FNPRoutedRejected).setTimeout(5000);
			// Ignore Rejected - let it be retried on other peers
			m = usm.waitFor(mf1/*.or(mf2)*/, null);
		} catch (DisconnectedException e) {
			Logger.normal(this, "Disconnected in waiting for pong");
			return -1;
		}
		if(m == null) return -1;
		if(m.getSpec() == DMT.FNPRoutedRejected) return -1;
		return m.getInt(DMT.COUNTER) - initialX;
	}

	/**
	 * Check the datastore, then if the key is not in the store,
	 * check whether another node is requesting the same key at
	 * the same HTL, and if all else fails, create a new 
	 * RequestSender for the key/htl.
	 * @param closestLocation The closest location to the key so far.
	 * @param localOnly If true, only check the datastore.
	 * @return A CHKBlock if the data is in the store, otherwise
	 * a RequestSender, unless the HTL is 0, in which case NULL.
	 * RequestSender.
	 */
	public Object makeRequestSender(Key key, short htl, long uid, PeerNode source, double closestLocation, boolean resetClosestLocation, boolean localOnly, boolean cache, boolean ignoreStore) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "makeRequestSender("+key+ ',' +htl+ ',' +uid+ ',' +source+") on "+getDarknetPortNumber());
		// In store?
		KeyBlock chk = null;
		if(!ignoreStore) {
			if(key instanceof NodeCHK) {
				chk = fetch((NodeCHK)key, !cache);
			} else if(key instanceof NodeSSK) {
				NodeSSK k = (NodeSSK)key;
				DSAPublicKey pubKey = k.getPubKey();
				if(pubKey == null) {
					pubKey = getKey(k.getPubKeyHash());
					if(logMINOR) Logger.minor(this, "Fetched pubkey: "+pubKey);
					try {
						k.setPubKey(pubKey);
					} catch (SSKVerifyException e) {
						Logger.error(this, "Error setting pubkey: "+e, e);
					}
				}
				if(pubKey != null) {
					if(logMINOR) Logger.minor(this, "Got pubkey: "+pubKey);
					chk = fetch((NodeSSK)key, !cache);
				} else {
					if(logMINOR) Logger.minor(this, "Not found because no pubkey: "+uid);
				}
			} else
				throw new IllegalStateException("Unknown key type: "+key.getClass());
			if(chk != null) return chk;
		}
		if(localOnly) return null;
		if(logMINOR) Logger.minor(this, "Not in store locally");
		
		// Transfer coalescing - match key only as HTL irrelevant
		RequestSender sender = null;
		synchronized(transferringRequestSenders) {
			sender = (RequestSender) transferringRequestSenders.get(key);
		}
		if(sender != null) {
			if(logMINOR) Logger.minor(this, "Data already being transferred: "+sender);
			return sender;
		}

		// HTL == 0 => Don't search further
		if(htl == 0) {
			if(logMINOR) Logger.minor(this, "No HTL");
			return null;
		}
		
		synchronized(requestSenders) {
			// Request coalescing
			KeyHTLPair kh = new KeyHTLPair(key, htl);
			sender = (RequestSender) requestSenders.get(kh);
			if(sender != null) {
				if(logMINOR) Logger.minor(this, "Found sender: "+sender+" for "+uid);
				return sender;
			}
			
			sender = new RequestSender(key, null, htl, uid, this, closestLocation, resetClosestLocation, source);
			// RequestSender adds itself to requestSenders
		}
		sender.start();
		if(logMINOR) Logger.minor(this, "Created new sender: "+sender);
		return sender;
	}
	
	static class KeyHTLPair {
		final Key key;
		final short htl;
		KeyHTLPair(Key key, short htl) {
			this.key = key;
			this.htl = htl;
		}
		
		public boolean equals(Object o) {
			if(o instanceof KeyHTLPair) {
				KeyHTLPair p = (KeyHTLPair) o;
				return (p.key.equals(key) && (p.htl == htl));
			} else return false;
		}
		
		public int hashCode() {
			return key.hashCode() ^ htl;
		}
		
		public String toString() {
			return key.toString()+ ':' +htl;
		}
	}

	/**
	 * Add a RequestSender to our HashMap.
	 */
	public void addRequestSender(Key key, short htl, RequestSender sender) {
		synchronized(requestSenders) {
			KeyHTLPair kh = new KeyHTLPair(key, htl);
			if(requestSenders.containsKey(kh)) {
				RequestSender rs = (RequestSender) requestSenders.get(kh);
				Logger.error(this, "addRequestSender(): KeyHTLPair '"+kh+"' already in requestSenders as "+rs+" and you want to add "+sender);
				return;
			}
			requestSenders.put(kh, sender);
		}
	}

	/**
	 * Add a AnyInsertSender to our HashMap.
	 */
	public void addInsertSender(Key key, short htl, AnyInsertSender sender) {
		synchronized(insertSenders) {
			KeyHTLPair kh = new KeyHTLPair(key, htl);
			if(insertSenders.containsKey(kh)) {
				AnyInsertSender is = (AnyInsertSender) insertSenders.get(kh);
				Logger.error(this, "addInsertSender(): KeyHTLPair '"+kh+"' already in insertSenders as "+is+" and you want to add "+sender);
				return;
			}
			insertSenders.put(kh, sender);
		}
	}

	/**
	 * Add a transferring RequestSender to our HashMap.
	 */
	public void addTransferringSender(NodeCHK key, RequestSender sender) {
		synchronized(transferringRequestSenders) {
			transferringRequestSenders.put(key, sender);
		}
	}
	
	void addTransferringRequestHandler(long id) {
		Long l = new Long(id);
		synchronized(transferringRequestHandlers) {
			transferringRequestHandlers.add(l);
		}
	}
	
	void removeTransferringRequestHandler(long id) {
		Long l = new Long(id);
		synchronized(transferringRequestHandlers) {
			transferringRequestHandlers.remove(l);
		}
	}

	public SSKBlock fetch(NodeSSK key, boolean dontPromote) {
		if(logMINOR) dumpStoreHits();
		try {
			SSKBlock block = sskDatastore.fetch(key, dontPromote);
			if(block != null) {
				return block;
			}
			return sskDatacache.fetch(key, dontPromote);
		} catch (IOException e) {
			Logger.error(this, "Cannot fetch data: "+e, e);
			return null;
		}
	}

	public CHKBlock fetch(NodeCHK key, boolean dontPromote) {
		if(logMINOR) dumpStoreHits();
		try {
			CHKBlock block = chkDatastore.fetch(key, dontPromote);
			if(block != null) return block;
			return chkDatacache.fetch(key, dontPromote);
		} catch (IOException e) {
			Logger.error(this, "Cannot fetch data: "+e, e);
			return null;
		}
	}
	
	public FreenetStore getChkDatacache() {
		return chkDatacache;
	}
	public FreenetStore getChkDatastore() {
		return chkDatastore;
	}
	public long getMaxTotalKeys() {
		return maxTotalKeys;
	}

	long timeLastDumpedHits;
	
	public void dumpStoreHits() {
		long now = System.currentTimeMillis();
		if(now - timeLastDumpedHits > 5000) {
			timeLastDumpedHits = now;
		} else return;
		Logger.minor(this, "Distribution of hits and misses over stores:\n"+
				"CHK Datastore: "+chkDatastore.hits()+ '/' +(chkDatastore.hits()+chkDatastore.misses())+ '/' +chkDatastore.keyCount()+
				"\nCHK Datacache: "+chkDatacache.hits()+ '/' +(chkDatacache.hits()+chkDatacache.misses())+ '/' +chkDatacache.keyCount()+
				"\nSSK Datastore: "+sskDatastore.hits()+ '/' +(sskDatastore.hits()+sskDatastore.misses())+ '/' +sskDatastore.keyCount()+
				"\nSSK Datacache: "+sskDatacache.hits()+ '/' +(sskDatacache.hits()+sskDatacache.misses())+ '/' +sskDatacache.keyCount());
	}
	
	public void store(CHKBlock block) {
		store(block, block.getKey().toNormalizedDouble());
	}
	
	/**
	 * Store a datum.
	 * @param deep If true, insert to the store as well as the cache. Do not set
	 * this to true unless the store results from an insert, and this node is the
	 * closest node to the target; see the description of chkDatastore.
	 */
	public void store(CHKBlock block, double loc) {
		boolean deep = !peers.isCloserLocation(loc);
		store(block, deep);
	}

	public void storeShallow(CHKBlock block) {
		store(block, false);
	}
	
	private void store(CHKBlock block, boolean deep) {
		try {
			if(deep) {
				chkDatastore.put(block);
			}
			chkDatacache.put(block);
			if(clientCore != null && clientCore.requestStarters != null)
				clientCore.requestStarters.chkFetchScheduler.tripPendingKey(block);
		} catch (IOException e) {
			Logger.error(this, "Cannot store data: "+e, e);
		}
	}
	
	/** Store the block if this is a sink. Call for inserts. */
	public void storeInsert(SSKBlock block) throws KeyCollisionException {
		store(block, block.getKey().toNormalizedDouble());
	}

	/** Store only to the cache, and not the store. Called by requests,
	 * as only inserts cause data to be added to the store. */
	public void storeShallow(SSKBlock block) throws KeyCollisionException {
		store(block, false);
	}
	
	private void store(SSKBlock block, boolean deep) throws KeyCollisionException {
		try {
			if(deep) {
				sskDatastore.put(block, false);
			}
			sskDatacache.put(block, false);
			cacheKey(((NodeSSK)block.getKey()).getPubKeyHash(), ((NodeSSK)block.getKey()).getPubKey(), deep);
			if(clientCore != null && clientCore.requestStarters != null)
				clientCore.requestStarters.sskFetchScheduler.tripPendingKey(block);
			
		} catch (IOException e) {
			Logger.error(this, "Cannot store data: "+e, e);
		}
	}
	
	/**
	 * Store a datum.
	 * @param deep If true, insert to the store as well as the cache. Do not set
	 * this to true unless the store results from an insert, and this node is the
	 * closest node to the target; see the description of chkDatastore.
	 */
	public void store(SSKBlock block, double loc) throws KeyCollisionException {
		boolean deep = !peers.isCloserLocation(loc);
		store(block, deep);
	}
	
	/**
	 * Remove a sender from the set of currently transferring senders.
	 */
	public void removeTransferringSender(NodeCHK key, RequestSender sender) {
		synchronized(transferringRequestSenders) {
			RequestSender rs = (RequestSender) transferringRequestSenders.remove(key);
			if(rs != sender) {
				Logger.error(this, "Removed "+rs+" should be "+sender+" for "+key+" in removeTransferringSender");
			}
		}
	}

	/**
	 * Remove a RequestSender from the map.
	 */
	public void removeRequestSender(Key key, short htl, RequestSender sender) {
		synchronized(requestSenders) {
			KeyHTLPair kh = new KeyHTLPair(key, htl);
			RequestSender rs = (RequestSender) requestSenders.remove(kh);
			if(rs != sender) {
				Logger.error(this, "Removed "+rs+" should be "+sender+" for "+key+ ',' +htl+" in removeRequestSender");
			}
			requestSenders.notifyAll();
		}
	}

	/**
	 * Remove an CHKInsertSender from the map.
	 */
	public void removeInsertSender(Key key, short htl, AnyInsertSender sender) {
		synchronized(insertSenders) {
			KeyHTLPair kh = new KeyHTLPair(key, htl);
			AnyInsertSender is = (AnyInsertSender) insertSenders.remove(kh);
			if(is != sender) {
				Logger.error(this, "Removed "+is+" should be "+sender+" for "+key+ ',' +htl+" in removeInsertSender");
			}
			insertSenders.notifyAll();
		}
	}

	final boolean decrementAtMax;
	final boolean decrementAtMin;
	
	/**
	 * Decrement the HTL according to the policy of the given
	 * NodePeer if it is non-null, or do something else if it is
	 * null.
	 */
	public short decrementHTL(PeerNode source, short htl) {
		if(source != null)
			return source.decrementHTL(htl);
		// Otherwise...
		if(htl >= maxHTL) htl = maxHTL;
		if(htl <= 0) htl = 1;
		if(htl == maxHTL) {
			if(decrementAtMax && !disableProbabilisticHTLs) htl--;
			return htl;
		}
		if(htl == 1) {
			if(decrementAtMin && !disableProbabilisticHTLs) htl--;
			return htl;
		}
		return --htl;
	}

	/**
	 * Fetch or create an CHKInsertSender for a given key/htl.
	 * @param key The key to be inserted.
	 * @param htl The current HTL. We can't coalesce inserts across
	 * HTL's.
	 * @param uid The UID of the caller's request chain, or a new
	 * one. This is obviously not used if there is already an 
	 * CHKInsertSender running.
	 * @param source The node that sent the InsertRequest, or null
	 * if it originated locally.
	 */
	public CHKInsertSender makeInsertSender(NodeCHK key, short htl, long uid, PeerNode source,
			byte[] headers, PartiallyReceivedBlock prb, boolean fromStore, double closestLoc, boolean cache) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "makeInsertSender("+key+ ',' +htl+ ',' +uid+ ',' +source+",...,"+fromStore);
		KeyHTLPair kh = new KeyHTLPair(key, htl);
		CHKInsertSender is = null;
		synchronized(insertSenders) {
			is = (CHKInsertSender) insertSenders.get(kh);
		}
		if(is != null) {
			if(logMINOR) Logger.minor(this, "Found "+is+" for "+kh);
			return is;
		}
  		if(fromStore && !cache)
			throw new IllegalArgumentException("From store = true but cache = false !!!");
		is = new CHKInsertSender(key, uid, headers, htl, source, this, prb, fromStore, closestLoc);
		is.start();
		if(logMINOR) Logger.minor(this, is.toString()+" for "+kh.toString());
		// CHKInsertSender adds itself to insertSenders
		return is;
	}
	
	/**
	 * Fetch or create an SSKInsertSender for a given key/htl.
	 * @param key The key to be inserted.
	 * @param htl The current HTL. We can't coalesce inserts across
	 * HTL's.
	 * @param uid The UID of the caller's request chain, or a new
	 * one. This is obviously not used if there is already an 
	 * SSKInsertSender running.
	 * @param source The node that sent the InsertRequest, or null
	 * if it originated locally.
	 */
	public SSKInsertSender makeInsertSender(SSKBlock block, short htl, long uid, PeerNode source,
			boolean fromStore, double closestLoc, boolean resetClosestLoc, boolean cache) {
		NodeSSK key = (NodeSSK) block.getKey();
		if(key.getPubKey() == null) {
			throw new IllegalArgumentException("No pub key when inserting");
		}
		if(cache)
			cacheKey(key.getPubKeyHash(), key.getPubKey(), !peers.isCloserLocation(block.getKey().toNormalizedDouble()));
		Logger.minor(this, "makeInsertSender("+key+ ',' +htl+ ',' +uid+ ',' +source+",...,"+fromStore);
		KeyHTLPair kh = new KeyHTLPair(key, htl);
		SSKInsertSender is = null;
		synchronized(insertSenders) {
			is = (SSKInsertSender) insertSenders.get(kh);
		}
		if(is != null) {
			Logger.minor(this, "Found "+is+" for "+kh);
			return is;
		}
		if(fromStore && !cache)
			throw new IllegalArgumentException("From store = true but cache = false !!!");
		is = new SSKInsertSender(block, uid, htl, source, this, fromStore, closestLoc);
		is.start();
		Logger.minor(this, is.toString()+" for "+kh.toString());
		// SSKInsertSender adds itself to insertSenders
		return is;
	}
	
	public boolean lockUID(long uid, boolean ssk, boolean insert) {
		if(logMINOR) Logger.minor(this, "Locking "+uid);
		Long l = new Long(uid);
		HashSet set = getUIDTracker(ssk, insert);
		synchronized(set) {
			set.add(l);
		}
		synchronized(runningUIDs) {
			if(runningUIDs.contains(l)) return false;
			runningUIDs.add(l);
			return true;
		}
	}
	
	public void unlockUID(long uid, boolean ssk, boolean insert) {
		if(logMINOR) Logger.minor(this, "Unlocking "+uid);
		Long l = new Long(uid);
		completed(uid);
		HashSet set = getUIDTracker(ssk, insert);
		synchronized(set) {
			set.remove(l);
		}
		synchronized(runningUIDs) {
			if(!runningUIDs.remove(l))
				throw new IllegalStateException("Could not unlock "+uid+ '!');
		}
	}

	HashSet getUIDTracker(boolean ssk, boolean insert) {
		if(ssk) {
			return insert ? runningSSKPutUIDs : runningSSKGetUIDs;
		} else {
			return insert ? runningCHKPutUIDs : runningCHKGetUIDs;
		}
	}
	
	/**
	 * @return Some status information.
	 */
	public String getStatus() {
		StringBuffer sb = new StringBuffer();
		if (peers != null)
			sb.append(peers.getStatus());
		else
			sb.append("No peers yet");
		sb.append("\nInserts: ");
		synchronized(insertSenders) {
			int x = getNumInsertSenders();
			sb.append(x);
			if((x < 5) && (x > 0)) {
				sb.append('\n');
				// Dump
				Iterator i = insertSenders.values().iterator();
				while(i.hasNext()) {
					AnyInsertSender s = (AnyInsertSender) i.next();
					sb.append(s.getUID());
					sb.append(": ");
					sb.append(s.getStatusString());
					sb.append('\n');
				}
			}
		}
		sb.append("\nRequests: ");
		sb.append(getNumRequestSenders());
		sb.append("\nTransferring requests: ");
		sb.append(getNumTransferringRequestSenders());
		sb.append('\n');
		return sb.toString();
	}

	/**
	 * @return TMCI peer list
	 */
	public String getTMCIPeerList() {
		StringBuffer sb = new StringBuffer();
		if (peers != null)
			sb.append(peers.getTMCIPeerList());
		else
			sb.append("No peers yet");
		return sb.toString();
	}
	
	public int getNumInsertSenders() {
		synchronized(insertSenders) {
			return insertSenders.size();
		}
	}
	
	public int getNumRequestSenders() {
		synchronized(requestSenders) {
			return requestSenders.size();
		}
	}

	public int getNumSSKRequests() {
		return runningSSKGetUIDs.size();
	}
	
	public int getNumCHKRequests() {
		return runningCHKGetUIDs.size();
	}
	
	public int getNumSSKInserts() {
		return runningSSKPutUIDs.size();
	}
	
	public int getNumCHKInserts() {
		return runningCHKPutUIDs.size();
	}
	
	public int getNumTransferringRequestSenders() {
		synchronized(transferringRequestSenders) {
			return transferringRequestSenders.size();
		}
	}
	
	public int getNumTransferringRequestHandlers() {
		synchronized(transferringRequestHandlers) {
			return transferringRequestHandlers.size();
		}
	}
	
	/**
	 * @return Data String for freeviz.
	 */
	public String getFreevizOutput() {
		StringBuffer sb = new StringBuffer();
		sb.append("\nrequests=");
		sb.append(getNumRequestSenders());
		
		sb.append("\ntransferring_requests=");
		sb.append(getNumTransferringRequestSenders());
		
		sb.append("\ninserts=");
		sb.append(getNumInsertSenders());
		sb.append('\n');
		
		
		if (peers != null)
			sb.append(peers.getFreevizOutput());
		  						
		return sb.toString();
	}

	final LRUQueue recentlyCompletedIDs;

	static final int MAX_RECENTLY_COMPLETED_IDS = 10*1000;
	/** Length of signature parameters R and S */
	static final int SIGNATURE_PARAMETER_LENGTH = 32;

	/**
	 * Has a request completed with this ID recently?
	 */
	public synchronized boolean recentlyCompleted(long id) {
		return recentlyCompletedIDs.contains(new Long(id));
	}
	
	/**
	 * A request completed (regardless of success).
	 */
	private synchronized void completed(long id) {
		recentlyCompletedIDs.push(new Long(id));
		while(recentlyCompletedIDs.size() > MAX_RECENTLY_COMPLETED_IDS)
			recentlyCompletedIDs.pop();
	}

	/**
	 * Look up a cached public key by its hash.
	 */
	public DSAPublicKey getKey(byte[] hash) {
		ImmutableByteArrayWrapper w = new ImmutableByteArrayWrapper(hash);
		if(logMINOR) Logger.minor(this, "Getting pubkey: "+HexUtil.bytesToHex(hash));
		if(USE_RAM_PUBKEYS_CACHE) {
			synchronized(cachedPubKeys) {
				DSAPublicKey key = (DSAPublicKey) cachedPubKeys.get(w);
				if(key != null) {
					cachedPubKeys.push(w, key);
					if(logMINOR) Logger.minor(this, "Got "+HexUtil.bytesToHex(hash)+" from cache");
					return key;
				}
			}
		}
		try {
			DSAPublicKey key;
			key = pubKeyDatastore.fetchPubKey(hash, false);
			if(key == null)
				key = pubKeyDatacache.fetchPubKey(hash, false);
			if(key != null) {
				cacheKey(hash, key, false);
				if(logMINOR) Logger.minor(this, "Got "+HexUtil.bytesToHex(hash)+" from store");
			}
			return key;
		} catch (IOException e) {
			// FIXME deal with disk full, access perms etc; tell user about it.
			Logger.error(this, "Error accessing pubkey store: "+e, e);
			return null;
		}
	}
	
	/**
	 * Cache a public key
	 */
	public void cacheKey(byte[] hash, DSAPublicKey key, boolean deep) {
		if(logMINOR) Logger.minor(this, "Cache key: "+HexUtil.bytesToHex(hash)+" : "+key);
		ImmutableByteArrayWrapper w = new ImmutableByteArrayWrapper(hash);
		synchronized(cachedPubKeys) {
			DSAPublicKey key2 = (DSAPublicKey) cachedPubKeys.get(w);
			if((key2 != null) && !key2.equals(key)) {
				MessageDigest md256 = SHA256.getMessageDigest();
				byte[] hashCheck = md256.digest(key.asBytes());
				if(Arrays.equals(hashCheck, hash)) {
					Logger.error(this, "Hash is correct!!!");
					// Verify the old key
					byte[] oldHash = md256.digest(key2.asBytes());
					if(Arrays.equals(oldHash, hash)) {
						Logger.error(this, "Old hash is correct too!! - Bug in DSAPublicKey.equals() or SHA-256 collision!");
					} else {
						Logger.error(this, "Old hash is wrong!");
						cachedPubKeys.removeKey(w);
						cacheKey(hash, key, deep);
					}
				} else {
					Logger.error(this, "New hash is wrong");
				}
				throw new IllegalArgumentException("Wrong hash?? Already have different key with same hash!");
			}
			cachedPubKeys.push(w, key);
			while(cachedPubKeys.size() > MAX_MEMORY_CACHED_PUBKEYS)
				cachedPubKeys.popKey();
		}
		try {
			if(deep) {
				pubKeyDatastore.put(hash, key);
				pubKeyDatastore.fetchPubKey(hash, true);
			}
			pubKeyDatacache.put(hash, key);
			pubKeyDatacache.fetchPubKey(hash, true);
		} catch (IOException e) {
			// FIXME deal with disk full, access perms etc; tell user about it.
			Logger.error(this, "Error accessing pubkey store: "+e, e);
		}
	}

	public boolean isTestnetEnabled() {
		return testnetEnabled;
	}

	public ClientKeyBlock fetchKey(ClientKey key, boolean dontPromote) throws KeyVerifyException {
		if(key instanceof ClientCHK)
			return fetch((ClientCHK)key, dontPromote);
		else if(key instanceof ClientSSK)
			return fetch((ClientSSK)key, dontPromote);
		else
			throw new IllegalStateException("Don't know what to do with "+key);
	}

	private ClientKeyBlock fetch(ClientSSK clientSSK, boolean dontPromote) throws SSKVerifyException {
		DSAPublicKey key = clientSSK.getPubKey();
		if(key == null) {
			key = getKey(clientSSK.pubKeyHash);
		}
		if(key == null) return null;
		clientSSK.setPublicKey(key);
		SSKBlock block = fetch((NodeSSK)clientSSK.getNodeKey(), dontPromote);
		if(block == null) return null;
		// Move the pubkey to the top of the LRU, and fix it if it
		// was corrupt.
		cacheKey(clientSSK.pubKeyHash, key, false);
		return ClientSSKBlock.construct(block, clientSSK);
	}

	private ClientKeyBlock fetch(ClientCHK clientCHK, boolean dontPromote) throws CHKVerifyException {
		CHKBlock block = fetch(clientCHK.getNodeCHK(), dontPromote);
		if(block == null) return null;
		return new ClientCHKBlock(block, clientCHK);
	}
	
	public void exit(int reason) {
		try {
			this.park();
			System.out.println("Goodbye.");
			System.out.println(reason);
		} finally {
			System.exit(reason);
		}
	}
	
	public void exit(String reason){
		try {
			this.park();
			System.out.println("Goodbye. from "+this+" ("+reason+ ')');
		} finally {
			System.exit(0);
		}
	}
	
	/**
	 * Get the node into a state where it can be stopped safely
	 * May be called twice - once in exit (above) and then again
	 * from the wrapper triggered by calling System.exit(). Beware!
	 */
	public synchronized void park() {
		if(isStopping) return;
		isStopping = true;
		
		config.store();
	}

	public NodeUpdateManager getNodeUpdater(){
		return nodeUpdater;
	}
	
	public DarknetPeerNode[] getDarknetConnections() {
		return peers.getDarknetPeers();
	}
	
	public boolean addDarknetConnection(PeerNode pn) {
		boolean retval = peers.addPeer(pn);
		peers.writePeers();
		return retval;
	}
	
	public void removeDarknetConnection(PeerNode pn) {
		peers.disconnect(pn);
	}

	public void onConnectedPeer() {
		if(logMINOR) Logger.minor(this, "onConnectedPeer()");
		ipDetector.onConnectedPeer();
	}
	
	public int getFNPPort(){
		return this.getDarknetPortNumber();
	}
	
	public synchronized boolean setNewestPeerLastGoodVersion( int version ) {
		if( version > buildOldAgeUserAlert.lastGoodVersion ) {
			if( buildOldAgeUserAlert.lastGoodVersion == 0 ) {
				clientCore.alerts.register(buildOldAgeUserAlert);
			}
			buildOldAgeUserAlert.lastGoodVersion = version;
			return true;
		}
		return false;
	}
	
	/**
	 * Handle a received node to node message
	 */
	public void receivedNodeToNodeMessage(Message m) {
	  PeerNode src = (PeerNode) m.getSource();
	  if(!(src instanceof DarknetPeerNode)) {
		Logger.error(this, "Got N2NTM from opennet node ?!?!?!: "+m+" from "+src);
		return;
	  }
	  DarknetPeerNode source = (DarknetPeerNode)m.getSource();
	  int type = ((Integer) m.getObject(DMT.NODE_TO_NODE_MESSAGE_TYPE)).intValue();
	  if(type == Node.N2N_MESSAGE_TYPE_FPROXY) {
		ShortBuffer messageData = (ShortBuffer) m.getObject(DMT.NODE_TO_NODE_MESSAGE_DATA);
		Logger.normal(this, "Received N2NM from '"+source.getPeer()+"'");
		SimpleFieldSet fs = null;
		try {
			fs = new SimpleFieldSet(new String(messageData.getData(), "UTF-8"), false, true);
		} catch (IOException e) {
			Logger.error(this, "IOException while parsing node to node message data", e);
			return;
		}
		if(fs.get("n2nType") != null) {
			fs.removeValue("n2nType");
		}
		fs.putOverwrite("n2nType", Integer.toString(type));
		if(fs.get("receivedTime") != null) {
			fs.removeValue("receivedTime");
		}
		fs.putOverwrite("receivedTime", Long.toString(System.currentTimeMillis()));
		if(fs.get("receivedAs") != null) {
			fs.removeValue("receivedAs");
		}
		fs.putOverwrite("receivedAs", "nodeToNodeMessage");
		int fileNumber = source.writeNewExtraPeerDataFile( fs, EXTRA_PEER_DATA_TYPE_N2NTM);
		if( fileNumber == -1 ) {
			Logger.error( this, "Failed to write N2NTM to extra peer data file for peer "+source.getPeer());
		}
		// Keep track of the fileNumber so we can potentially delete the extra peer data file later, the file is authoritative
		try {
			handleNodeToNodeTextMessageSimpleFieldSet(fs, source, fileNumber);
		} catch (FSParseException e) {
			// Shouldn't happen
			throw new Error(e);
		}
	  } else {
		Logger.error(this, "Received unknown node to node message type '"+type+"' from "+source.getPeer());
	  }
	}

	/**
	 * Handle a node to node text message SimpleFieldSet
	 * @throws FSParseException 
	 */
	public void handleNodeToNodeTextMessageSimpleFieldSet(SimpleFieldSet fs, DarknetPeerNode source, int fileNumber) throws FSParseException {
	  if(logMINOR)
		  Logger.minor(this, "Got node to node message: \n"+fs);
	  int overallType = fs.getInt("n2nType", 1); // FIXME remove default
	  fs.removeValue("n2nType");
	  if(overallType == Node.N2N_MESSAGE_TYPE_FPROXY) {
		  handleFproxyNodeToNodeTextMessageSimpleFieldSet(fs, source, fileNumber);
	  } else {
		  Logger.error(this, "Received unknown node to node message type '"+overallType+"' from "+source.getPeer());  
	  }
	}

	private void handleFproxyNodeToNodeTextMessageSimpleFieldSet(SimpleFieldSet fs, DarknetPeerNode source, int fileNumber) throws FSParseException {
		int type = fs.getInt("type");
		if(type == Node.N2N_TEXT_MESSAGE_TYPE_USERALERT) {
			source.handleFproxyN2NTM(fs, fileNumber);
		} else if(type == Node.N2N_TEXT_MESSAGE_TYPE_FILE_OFFER) {
			source.handleFproxyFileOffer(fs, fileNumber);
		} else if(type == Node.N2N_TEXT_MESSAGE_TYPE_FILE_OFFER_ACCEPTED) {
			source.handleFproxyFileOfferAccepted(fs, fileNumber);
		} else if(type == Node.N2N_TEXT_MESSAGE_TYPE_FILE_OFFER_REJECTED) {
			source.handleFproxyFileOfferRejected(fs, fileNumber);
		} else {
			Logger.error(this, "Received unknown fproxy node to node message sub-type '"+type+"' from "+source.getPeer());
		}
	}

	public String getMyName() {
	  return myName;
	}

	public MessageCore getUSM() {
	  return usm;
	}

	public int getSwaps() {
		return LocationManager.swaps;
	}

	public int getNoSwaps() {
		return LocationManager.noSwaps;
	}

	public int getStartedSwaps() {
		return LocationManager.startedSwaps;
	}

	public int getSwapsRejectedAlreadyLocked() {
		return LocationManager.swapsRejectedAlreadyLocked;
	}

	public int getSwapsRejectedLoop() {
		return LocationManager.swapsRejectedLoop;
	}

	public int getSwapsRejectedNowhereToGo() {
		return LocationManager.swapsRejectedNowhereToGo;
	}

	public int getSwapsRejectedRateLimit() {
		return LocationManager.swapsRejectedRateLimit;
	}

	public int getSwapsRejectedRecognizedID() {
		return LocationManager.swapsRejectedRecognizedID;
	}

	public PeerNode[] getPeerNodes() {
		return peers.myPeers;
	}
	
	public PeerNode[] getConnectedPeers() {
		return peers.connectedPeers;
	}
	
	/**
	 * Return a peer of the node given its ip and port, name or identity, as a String
	 */
	public DarknetPeerNode getPeerNode(String nodeIdentifier) {
		DarknetPeerNode[] pn = peers.getDarknetPeers();
		for(int i=0;i<pn.length;i++)
		{
			Peer peer = pn[i].getPeer();
			String nodeIpAndPort = "";
			if(peer != null) {
				nodeIpAndPort = peer.toString();
			}
			String name = pn[i].myName;
			String identity = pn[i].getIdentityString();
			if(identity.equals(nodeIdentifier) || nodeIpAndPort.equals(nodeIdentifier) || name.equals(nodeIdentifier))
			{
				return pn[i];
			}
		}
		return null;
	}

	public boolean isHasStarted() {
		return hasStarted;
	}

	public void queueRandomReinsert(KeyBlock block) {
		clientCore.queueRandomReinsert(block);
	}

	public String getExtraPeerDataDir() {
		return extraPeerDataDir.getPath();
	}

	public boolean noConnectedPeers() {
		return !peers.anyConnectedPeers();
	}

	public double getLocation() {
		return lm.loc.getValue();
	}

	public double getLocationChangeSession() {
		return lm.locChangeSession;
	}
	
	public int getNumberOfRemotePeerLocationsSeenInSwaps() {
		return lm.numberOfRemotePeerLocationsSeenInSwaps;
	}
	
	public boolean isAdvancedModeEnabled() {
		return clientCore.isAdvancedModeEnabled();
	}
	
	public boolean isFProxyJavascriptEnabled() {
		return clientCore.isFProxyJavascriptEnabled();
	}
	
	// FIXME convert these kind of threads to Checkpointed's and implement a handler
	// using the PacketSender/Ticker. Would save a few threads.
	
	public int getNumARKFetchers() {
		PeerNode[] p = peers.myPeers;
		int x = 0;
		for(int i=0;i<p.length;i++) {
			if(p[i].isFetchingARK()) x++;
		}
		return x;
	}
	
	// FIXME put this somewhere else
	private volatile Object statsSync = new Object();
	/** The total number of bytes of real data i.e. payload sent by the node */
	private long totalPayloadSent;
	
	public void sentPayload(int len) {
		synchronized(statsSync) {
			totalPayloadSent += len;
		}
	}
	
	public long getTotalPayloadSent() {
		synchronized(statsSync) {
			return totalPayloadSent;
		}
	}

	public void setName(String key) throws InvalidConfigValueException {
		 config.get("node").getOption("name").setValue(key);
	}

	public Ticker getTicker() {
		return ps;
	}

	public int getUnclaimedFIFOSize() {
		return usm.getUnclaimedFIFOSize();
	}

	/** 
	 * Connect this node to another node (for purposes of testing) 
	 */
	public void connect(Node node) throws FSParseException, PeerParseException, ReferenceSignatureVerificationException {
		peers.connect(node.darknetCrypto.exportPublicFieldSet(), darknetCrypto.packetMangler);
	}
	
	public short maxHTL() {
		return maxHTL;
	}

	public int getDarknetPortNumber() {
		return darknetCrypto.portNumber;
	}

	public void JEStatsDump() {
		try { 
			System.out.println(storeEnvironment.getStats(statsConf));
		}
		catch(DatabaseException e) {
			System.out.println("Failed to get stats from JE environment: " + e);
		}
	}

	public int getOutputBandwidthLimit() {
		return outputBandwidthLimit;
	}
	
	public synchronized int getInputBandwidthLimit() {
		if(inputLimitDefault)
			return outputBandwidthLimit * 4;
		return inputBandwidthLimit;
	}

	public synchronized void setTimeSkewDetectedUserAlert() {
		if(timeSkewDetectedUserAlert == null) {
			timeSkewDetectedUserAlert = new TimeSkewDetectedUserAlert();
			clientCore.alerts.register(timeSkewDetectedUserAlert);
		}
	}

	public File getNodeDir() {
		return nodeDir;
	}

	public DarknetPeerNode createNewDarknetNode(SimpleFieldSet fs) throws FSParseException, PeerParseException, ReferenceSignatureVerificationException {
    	return new DarknetPeerNode(fs, this, darknetCrypto, peers, false, darknetCrypto.packetMangler);
	}

	public byte[] getDarknetIdentity() {
		return darknetCrypto.myIdentity;
	}

	public int estimateFullHeadersLengthOneMessage() {
		return darknetCrypto.packetMangler.fullHeadersLengthOneMessage();
	}

	public synchronized boolean isOpennetEnabled() {
		return opennet != null;
	}

	public SimpleFieldSet exportDarknetPublicFieldSet() {
		return darknetCrypto.exportPublicFieldSet();
	}

	public SimpleFieldSet exportOpennetPublicFieldSet() {
		return opennet.crypto.exportPublicFieldSet();
	}

	public SimpleFieldSet exportDarknetPrivateFieldSet() {
		return darknetCrypto.exportPrivateFieldSet();
	}
}
