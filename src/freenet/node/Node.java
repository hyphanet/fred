/* Freenet 0.7 node. */
package freenet.node;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
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
import java.util.Set;
import java.util.Vector;

import org.spaceroots.mantissa.random.MersenneTwister;
import org.tanukisoftware.wrapper.WrapperManager;

import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.EnvironmentMutableConfig;
import com.sleepycat.je.StatsConfig;

import freenet.client.FetchContext;
import freenet.clients.http.SimpleToadletServer;
import freenet.config.EnumerableOptionCallback;
import freenet.config.FreenetFilePersistentConfig;
import freenet.config.InvalidConfigValueException;
import freenet.config.LongOption;
import freenet.config.NodeNeedRestartException;
import freenet.config.PersistentConfig;
import freenet.config.SubConfig;
import freenet.crypt.DSAPublicKey;
import freenet.crypt.DiffieHellman;
import freenet.crypt.RandomSource;
import freenet.crypt.SHA256;
import freenet.crypt.Yarrow;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.FreenetInetAddress;
import freenet.io.comm.IOStatisticCollector;
import freenet.io.comm.Message;
import freenet.io.comm.MessageCore;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.Peer;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.io.comm.UdpSocketHandler;
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
import freenet.node.NodeDispatcher.NodeDispatcherCallback;
import freenet.node.updater.NodeUpdateManager;
import freenet.node.useralerts.AbstractUserAlert;
import freenet.node.useralerts.BuildOldAgeUserAlert;
import freenet.node.useralerts.ClockProblemDetectedUserAlert;
import freenet.node.useralerts.ExtOldAgeUserAlert;
import freenet.node.useralerts.MeaningfulNodeNameUserAlert;
import freenet.node.useralerts.NotEnoughNiceLevelsUserAlert;
import freenet.node.useralerts.OpennetUserAlert;
import freenet.node.useralerts.SimpleUserAlert;
import freenet.node.useralerts.TimeSkewDetectedUserAlert;
import freenet.node.useralerts.UserAlert;
import freenet.pluginmanager.ForwardPort;
import freenet.pluginmanager.PluginManager;
import freenet.store.BerkeleyDBFreenetStore;
import freenet.store.CHKStore;
import freenet.store.FreenetStore;
import freenet.store.KeyCollisionException;
import freenet.store.PubkeyStore;
import freenet.store.RAMFreenetStore;
import freenet.store.SSKStore;
import freenet.support.DoubleTokenBucket;
import freenet.support.Executor;
import freenet.support.Fields;
import freenet.support.FileLoggerHook;
import freenet.support.HTMLEncoder;
import freenet.support.HTMLNode;
import freenet.support.HexUtil;
import freenet.support.ImmutableByteArrayWrapper;
import freenet.support.LRUHashtable;
import freenet.support.LRUQueue;
import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.PooledExecutor;
import freenet.support.ShortBuffer;
import freenet.support.SimpleFieldSet;
import freenet.support.api.BooleanCallback;
import freenet.support.api.IntCallback;
import freenet.support.api.LongCallback;
import freenet.support.api.ShortCallback;
import freenet.support.api.StringCallback;
import freenet.support.io.ArrayBucketFactory;
import freenet.support.io.Closer;
import freenet.support.io.FileUtil;
import freenet.support.io.NativeThread;
import freenet.support.transport.ip.HostnameSyntaxException;

/**
 * @author amphibian
 */
public class Node implements TimeSkewDetectorCallback, GetPubkey {

	private static boolean logMINOR;
	
	private static MeaningfulNodeNameUserAlert nodeNameUserAlert;
	private static BuildOldAgeUserAlert buildOldAgeUserAlert;
	private static TimeSkewDetectedUserAlert timeSkewDetectedUserAlert;
	private final static ClockProblemDetectedUserAlert clockProblemDetectedUserAlert = new ClockProblemDetectedUserAlert();
	
	public class NodeNameCallback extends StringCallback  {
		GetPubkey node;
	
		NodeNameCallback(GetPubkey n) {
			node=n;
		}
		public String get() {
			String name;
			synchronized(this) {
				name = myName;
			}
			if(name.startsWith("Node id|")|| name.equals("MyFirstFreenetNode")){
				clientCore.alerts.register(nodeNameUserAlert);
			}else{
				clientCore.alerts.unregister(nodeNameUserAlert);
			}
			return name;
		}

		public void set(String val) throws InvalidConfigValueException {
			if(get().equals(val)) return;
			else if(val.length() > 128)
				throw new InvalidConfigValueException("The given node name is too long ("+val+')');
			else if("".equals(val))
				val = "~none~";
			synchronized(this) {
				myName = val;
			}
			// We'll broadcast the new name to our connected darknet peers via a differential node reference
			SimpleFieldSet fs = new SimpleFieldSet(true);
			fs.putSingle("myName", myName);
			peers.locallyBroadcastDiffNodeRef(fs, true, false);
			// We call the callback once again to ensure MeaningfulNodeNameUserAlert
			// has been unregistered ... see #1595
			get();
		}
	}
	
	private class StoreTypeCallback extends StringCallback implements EnumerableOptionCallback {
		private String cachedStoreType;

		public String get() {
			if (cachedStoreType == null)
				cachedStoreType = storeType;
			return cachedStoreType;
		}

		public void set(String val) throws InvalidConfigValueException, NodeNeedRestartException {
			if (val.equals(storeType))
				return;

			boolean found = false;
			for (String p : getPossibleValues()) {
				if (p.equals(val)) {
					found = true;
					break;
				}
			}
			if (!found)
				throw new InvalidConfigValueException("Invalid store type");
			
			cachedStoreType = val;
			throw new NodeNeedRestartException("Store type cannot be changed on the fly");
		}

		public String[] getPossibleValues() {
			return new String[] { "bdb-index", "ram" };
		}

		public void setPossibleValues(String[] val) {
			throw new UnsupportedOperationException();
		}
	}
	
	private static class L10nCallback extends StringCallback implements EnumerableOptionCallback {
		
		public String get() {
			return L10n.mapLanguageNameToLongName(L10n.getSelectedLanguage());
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
			String[] result = new String[L10n.AVAILABLE_LANGUAGES.length];
			for(int i=0; i<L10n.AVAILABLE_LANGUAGES.length; i++)
				result[i] = L10n.AVAILABLE_LANGUAGES[i][1];
			return result;
		}
	}
	
	/** Stats */
	public final NodeStats nodeStats;
	public final NetworkIDManager netid;
	
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
	public static final double DECREMENT_AT_MAX_PROB = 0.1;
	// Send keepalives every 14-28 seconds. Comfortably fits within 30 second timeout.
	// If the packet is dropped, we will send ack requests etc, so this should be fine.
	public static final int KEEPALIVE_INTERVAL = 14000;
	// If no activity for 30 seconds, node is dead
	public static final int MAX_PEER_INACTIVITY = 60000;
	/** Time after which a handshake is assumed to have failed. */
	public static final int HANDSHAKE_TIMEOUT = 4800; // Keep the below within the 30 second assumed timeout.
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
	static final int MIN_INTERVAL_BETWEEN_INCOMING_PROBE_REQUESTS = 1000;
	public static final int SYMMETRIC_KEY_LENGTH = 32; // 256 bits - note that this isn't used everywhere to determine it
	/** Minimum space for zipped logfiles on testnet */
	static final long TESTNET_MIN_MAX_ZIPPED_LOGFILES = 512*1024*1024;
	static final String TESTNET_MIN_MAX_ZIPPED_LOGFILES_STRING = "512M";
	
	/** Datastore directory */
	private final File storeDir;
	
	private final String storeType;

	/** The number of bytes per key total in all the different datastores. All the datastores
	 * are always the same size in number of keys. */
	static final int sizePerKey = CHKBlock.DATA_LENGTH + CHKBlock.TOTAL_HEADERS_LENGTH +
		DSAPublicKey.PADDED_SIZE + SSKBlock.DATA_LENGTH + SSKBlock.TOTAL_HEADERS_LENGTH;
	
	/** The maximum number of keys stored in each of the datastores, cache and store combined. */
	private long maxTotalKeys;
	long maxCacheKeys;
	long maxStoreKeys;
	/** The maximum size of the datastore. Kept to avoid rounding turning 5G into 5368698672 */
	private long maxTotalDatastoreSize;
	/** If true, store shrinks occur immediately even if they are over 10% of the store size. If false,
	 * we just set the storeSize and do an offline shrink on the next startup. Online shrinks do not 
	 * preserve the most recently used data so are not recommended. */
	private boolean storeForceBigShrinks;
	
	/* These are private because must be protected by synchronized(this) */
	private final Environment storeEnvironment;
	private final EnvironmentMutableConfig envMutableConfig;
	private final SemiOrderedShutdownHook shutdownHook;
	private long databaseMaxMemory;
	/** The CHK datastore. Long term storage; data should only be inserted here if
	 * this node is the closest location on the chain so far, and it is on an 
	 * insert (because inserts will always reach the most specialized node; if we
	 * allow requests to store here, then we get pollution by inserts for keys not
	 * close to our specialization). These conclusions derived from Oskar's simulations. */
	private final CHKStore chkDatastore;
	/** The SSK datastore. See description for chkDatastore. */
	private final SSKStore sskDatastore;
	/** The store of DSAPublicKeys (by hash). See description for chkDatastore. */
	private final PubkeyStore pubKeyDatastore;
	/** The CHK datacache. Short term cache which stores everything that passes
	 * through this node. */
	private final CHKStore chkDatacache;
	/** The SSK datacache. Short term cache which stores everything that passes
	 * through this node. */
	private final SSKStore sskDatacache;
	/** The public key datacache (by hash). Short term cache which stores 
	 * everything that passes through this node. */
	private final PubkeyStore pubKeyDatacache;
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
	private final HashSet runningLocalCHKGetUIDs;
	private final HashSet runningSSKGetUIDs;
	private final HashSet runningLocalSSKGetUIDs;
	private final HashSet runningCHKPutUIDs;
	private final HashSet runningLocalCHKPutUIDs;
	private final HashSet runningSSKPutUIDs;
	private final HashSet runningLocalSSKPutUIDs;
	private final HashSet runningCHKOfferReplyUIDs;
	private final HashSet runningSSKOfferReplyUIDs;
	
	/** Semi-unique ID for swap requests. Used to identify us so that the
	 * topology can be reconstructed. */
	public long swapIdentifier;
	private String myName;
	public final LocationManager lm;
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
	
	NodeCrypto darknetCrypto;
	
	// Opennet stuff
	
	private final NodeCryptoConfig opennetCryptoConfig;
	private OpennetManager opennet;
	private volatile boolean isAllowedToConnectToSeednodes;
	private int maxOpennetPeers;
	private boolean acceptSeedConnections;
	private boolean passOpennetRefsThroughDarknet;
	
	// General stuff
	
	public final Executor executor;
	public final PacketSender ps;
	final DNSRequester dnsr;
	final NodeDispatcher dispatcher;
	public final UptimeEstimator uptime;
	static final int MAX_MEMORY_CACHED_PUBKEYS = 1000;
	final LRUHashtable cachedPubKeys;
	final boolean testnetEnabled;
	final TestnetHandler testnetHandler;
	public final DoubleTokenBucket outputThrottle;
	public boolean throttleLocalData;
	private int outputBandwidthLimit;
	private int inputBandwidthLimit;
	boolean inputLimitDefault;
	final boolean enableARKs;
	final boolean enablePerNodeFailureTables;
	final boolean enableULPRDataPropagation;
	final boolean enableSwapping;
	private volatile boolean publishOurPeersLocation;
	private volatile boolean routeAccordingToOurPeersLocation;
	boolean enableSwapQueueing;
	boolean enablePacketCoalescing;
	public static final short DEFAULT_MAX_HTL = (short)10;
	private short maxHTL;
	public final IOStatisticCollector collector;
	/** Type identifier for fproxy node to node messages, as sent on DMT.nodeToNodeMessage's */
	public static final int N2N_MESSAGE_TYPE_FPROXY = 1;
	/** Type identifier for differential node reference messages, as sent on DMT.nodeToNodeMessage's */
	public static final int N2N_MESSAGE_TYPE_DIFFNODEREF = 2;
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
	public static final int EXTRA_PEER_DATA_TYPE_QUEUED_TO_SEND_N2NM = 3;
	public static final int PEER_NOTE_TYPE_PRIVATE_DARKNET_COMMENT = 1;
	
	/** The bootID of the last time the node booted up. Or -1 if we don't know due to
	 * permissions problems, or we suspect that the node has been booted and not
	 * written the file e.g. if we can't write it. So if we want to compare data 
	 * gathered in the last session and only recorded to disk on a clean shutdown
	 * to data we have now, we just include the lastBootID. */
	public final long lastBootID;
	public final long bootID;
	public final long startupTime;

	private SimpleToadletServer toadlets;
	
	public final NodeClientCore clientCore;
	
	// ULPRs, RecentlyFailed, per node failure tables, are all managed by FailureTable.
	final FailureTable failureTable;
	
	// The version we were before we restarted.
	public int lastVersion;
	
	/** NodeUpdater **/
	public final NodeUpdateManager nodeUpdater;
	
	// Things that's needed to keep track of
	public final PluginManager pluginManager;
	
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
	 * Minimum uptime for us to consider a node an acceptable place to store a key. We store a key
	 * to the datastore only if it's from an insert, and we are a sink, but when calculating whether
	 * we are a sink we ignore nodes which have less uptime (percentage) than this parameter.
	 */
	private static final int MIN_UPTIME_STORE_KEY = 40;
	
	private volatile boolean isPRNGReady = false;
	
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
					p = new Peer(udp[i], false, true);
				} catch (HostnameSyntaxException e) {
					Logger.error(this, "Invalid hostname or IP Address syntax error while parsing our darknet node reference: "+udp[i]);
					System.err.println("Invalid hostname or IP Address syntax error while parsing our darknet node reference: "+udp[i]);
					continue;
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
		try {
			lm.setLocation(Location.getLocation(loc));
		} catch (FSParseException e) {
			IOException e1 = new IOException();
			e1.initCause(e);
			throw e1;
		}
		myName = fs.get("myName");
		if(myName == null) {
			myName = newName();
		}
		
		String verString = fs.get("version");
		if(verString == null) {
			Logger.error(this, "No version!");
			System.err.println("No version!");
		} else {
			lastVersion = Version.getArbitraryBuildNumber(verString, -1);
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
			fs.writeTo(fos);
			FileUtil.renameTo(backup, orig);
		} catch (IOException ioe) {
			Logger.error(this, "IOE :"+ioe.getMessage(), ioe);
			return;
		} finally {
			Closer.close(fos);
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
	 * a production node. Yarrow will be used if that parameter is null
	 * @param weakRandom The fast random number generator the node will use. If null a MT
	 * instance will be used, seeded from the secure PRNG.
	 * @param the loggingHandler
	 * @throws NodeInitException If the node initialization fails.
	 */
	 Node(PersistentConfig config, RandomSource r, RandomSource weakRandom, LoggingConfigHandler lc, NodeStarter ns, Executor executor) throws NodeInitException {
		// Easy stuff
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		String tmp = "Initializing Node using Freenet Build #"+Version.buildNumber()+" r"+Version.cvsRevision+" and freenet-ext Build #"+NodeStarter.extBuildNumber+" r"+NodeStarter.extRevisionNumber+" with "+System.getProperty("java.vendor")+" JVM version "+System.getProperty("java.version")+" running on "+System.getProperty("os.arch")+' '+System.getProperty("os.name")+' '+System.getProperty("os.version");
		Logger.normal(this, tmp);
		System.out.println(tmp);
		collector = new IOStatisticCollector();
		this.executor = executor;
		nodeStarter=ns;
		if(logConfigHandler != lc)
			logConfigHandler=lc;
		startupTime = System.currentTimeMillis();
		SimpleFieldSet oldConfig = config.getSimpleFieldSet();
		// Setup node-specific configuration
		SubConfig nodeConfig = new SubConfig("node", config);
		
		int sortOrder = 0;
		
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
		
		// FProxy config needs to be here too
		SubConfig fproxyConfig = new SubConfig("fproxy", config);
		try {
			toadlets = new SimpleToadletServer(fproxyConfig, new ArrayBucketFactory(), executor);
			fproxyConfig.finishedInitialization();
			toadlets.start();
		} catch (IOException e4) {
			Logger.error(this, "Could not start web interface: "+e4, e4);
			System.err.println("Could not start web interface: "+e4);
			e4.printStackTrace();
			throw new NodeInitException(NodeInitException.EXIT_COULD_NOT_START_FPROXY, "Could not start FProxy: "+e4);
		} catch (InvalidConfigValueException e4) {
			System.err.println("Invalid config value, cannot start web interface: "+e4);
			e4.printStackTrace();
			throw new NodeInitException(NodeInitException.EXIT_COULD_NOT_START_FPROXY, "Could not start FProxy: "+e4);
		}

		// Setup RNG if needed : DO NOT USE IT BEFORE THAT POINT!
		if(r == null) {
			final NativeThread entropyGatheringThread = new NativeThread(new Runnable() {

				private void recurse(File f) {
					if(isPRNGReady)
						return;
					File[] subDirs = f.listFiles(new FileFilter() {

						public boolean accept(File pathname) {
							return pathname.exists() && pathname.canRead() && pathname.isDirectory();
						}
					});


					// @see http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=5086412
					if(subDirs != null) 
						for(File currentDir : subDirs)
							recurse(currentDir);
				}

				public void run() {
					for(File root : File.listRoots()) {
						if(isPRNGReady)
							return;
						recurse(root);
					}
				}
			}, "Entropy Gathering Thread", NativeThread.MIN_PRIORITY, true);

			entropyGatheringThread.start();
			this.random = new Yarrow();
			DiffieHellman.init(random);
			
		} else // if it's not null it's because we are running in the simulator
			this.random = r;
		isPRNGReady = true;
		toadlets.getStartupToadlet().setIsPRNGReady();
		if(weakRandom == null) {
			byte buffer[] = new byte[16];
			random.nextBytes(buffer);
			this.fastWeakRandom = new MersenneTwister(buffer);
		}else
			this.fastWeakRandom = weakRandom;

		nodeNameUserAlert = new MeaningfulNodeNameUserAlert(this);
		recentlyCompletedIDs = new LRUQueue();
		this.config = config;
		cachedPubKeys = new LRUHashtable();
		lm = new LocationManager(random, this);

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
		runningLocalCHKGetUIDs = new HashSet();
		runningSSKGetUIDs = new HashSet();
		runningLocalSSKGetUIDs = new HashSet();
		runningCHKPutUIDs = new HashSet();
		runningLocalCHKPutUIDs = new HashSet();
		runningSSKPutUIDs = new HashSet();
		runningLocalSSKPutUIDs = new HashSet();
		runningCHKOfferReplyUIDs = new HashSet();
		runningSSKOfferReplyUIDs = new HashSet();
		
		// Directory for node-related files other than store
		
		nodeConfig.register("nodeDir", ".", sortOrder++, true, true /* because can't be changed on the fly, also for packages */, "Node.nodeDir", "Node.nodeDirLong", 
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
					public boolean isReadOnly() {
				        return true;
			        }
		});
		
		nodeDir = new File(nodeConfig.getString("nodeDir"));
		if(!((nodeDir.exists() && nodeDir.isDirectory()) || (nodeDir.mkdir()))) {
			String msg = "Could not find or create datastore directory";
			throw new NodeInitException(NodeInitException.EXIT_BAD_NODE_DIR, msg);
		}
		
		// Boot ID
		bootID = random.nextLong();
		// Fixed length file containing boot ID. Accessed with random access file. So hopefully it will always be
		// written. Note that we set lastBootID to -1 if we can't _write_ our ID as well as if we can't read it,
		// because if we can't write it then we probably couldn't write it on the last bootup either.
		File bootIDFile = new File(nodeDir, "bootID");
		int BOOT_FILE_LENGTH = 64 / 4; // A long in padded hex bytes
		long oldBootID = -1;
		RandomAccessFile raf = null;
		try {
			raf = new RandomAccessFile(bootIDFile, "rw");
			if(raf.length() < BOOT_FILE_LENGTH) {
				oldBootID = -1;
			} else {
				byte[] buf = new byte[BOOT_FILE_LENGTH];
				raf.readFully(buf);
				String s = new String(buf, "ISO-8859-1");
				try {
					oldBootID = Fields.bytesToLong(HexUtil.hexToBytes(s));
				} catch (NumberFormatException e) {
					oldBootID = -1;
				}
				raf.seek(0);
			}
			String s = HexUtil.bytesToHex(Fields.longToBytes(bootID));
			byte[] buf = s.getBytes("ISO-8859-1");
			if(buf.length != BOOT_FILE_LENGTH)
				System.err.println("Not 16 bytes for boot ID "+bootID+" - WTF??");
			raf.write(buf);
		} catch (IOException e) {
			oldBootID = -1;
			// If we have an error in reading, *or in writing*, we don't reliably know the last boot ID.
		} finally {
			try {
				if(raf != null)
					raf.close();
			} catch (IOException e) {
				// Ignore
			}
		}
		lastBootID = oldBootID;
		
		buildOldAgeUserAlert = new BuildOldAgeUserAlert();

		nodeConfig.register("disableProbabilisticHTLs", false, sortOrder++, true, false, "Node.disablePHTLS", "Node.disablePHTLSLong", 
				new BooleanCallback() {

					public Boolean get() {
						return disableProbabilisticHTLs;
					}

					public void set(Boolean val) throws InvalidConfigValueException {
						disableProbabilisticHTLs = val;
					}
			
		});
		
		disableProbabilisticHTLs = nodeConfig.getBoolean("disableProbabilisticHTLs");
		
		nodeConfig.register("maxHTL", DEFAULT_MAX_HTL, sortOrder++, true, false, "Node.maxHTL", "Node.maxHTLLong", new ShortCallback() {

					public Short get() {
						return maxHTL;
					}

					public void set(Short val) throws InvalidConfigValueException {
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
		
		// FIXME maybe these configs should actually be under a node.ip subconfig?
		ipDetector = new NodeIPDetector(this);
		sortOrder = ipDetector.registerConfigs(nodeConfig, sortOrder);
		
		// ARKs enabled?
		
		nodeConfig.register("enableARKs", true, sortOrder++, true, false, "Node.enableARKs", "Node.enableARKsLong", new BooleanCallback() {

			public Boolean get() {
				return enableARKs;
			}

			public void set(Boolean val) throws InvalidConfigValueException {
				throw new InvalidConfigValueException("Cannot change on the fly");
			}

			public boolean isReadOnly() {
				        return true;
			        }			
		});
		enableARKs = nodeConfig.getBoolean("enableARKs");
		
		nodeConfig.register("enablePerNodeFailureTables", true, sortOrder++, true, false, "Node.enablePerNodeFailureTables", "Node.enablePerNodeFailureTablesLong", new BooleanCallback() {

			public Boolean get() {
				return enablePerNodeFailureTables;
			}

			public void set(Boolean val) throws InvalidConfigValueException {
				throw new InvalidConfigValueException("Cannot change on the fly");
			}

			public boolean isReadOnly() {
				        return true;
			      }			
		});
		enablePerNodeFailureTables = nodeConfig.getBoolean("enablePerNodeFailureTables");
		
		nodeConfig.register("enableULPRDataPropagation", true, sortOrder++, true, false, "Node.enableULPRDataPropagation", "Node.enableULPRDataPropagationLong", new BooleanCallback() {

			public Boolean get() {
				return enableULPRDataPropagation;
			}

			public void set(Boolean val) throws InvalidConfigValueException {
				throw new InvalidConfigValueException("Cannot change on the fly");
			}

			public boolean isReadOnly() {
				        return true;
			        }			
		});
		enableULPRDataPropagation = nodeConfig.getBoolean("enableULPRDataPropagation");
		
		nodeConfig.register("enableSwapping", true, sortOrder++, true, false, "Node.enableSwapping", "Node.enableSwappingLong", new BooleanCallback() {
			
			public Boolean get() {
				return enableSwapping;
			}

			public void set(Boolean val) throws InvalidConfigValueException {
				throw new InvalidConfigValueException("Cannot change on the fly");
			}

			public boolean isReadOnly() {
				        return true;
			        }			
		});
		enableSwapping = nodeConfig.getBoolean("enableSwapping");
		
		nodeConfig.register("publishOurPeersLocation", false, sortOrder++, true, false, "Node.publishOurPeersLocation", "Node.publishOurPeersLocationLong", new BooleanCallback() {

			public Boolean get() {
				return publishOurPeersLocation;
			}

			public void set(Boolean val) throws InvalidConfigValueException {
				publishOurPeersLocation = val;
			}
		});
		publishOurPeersLocation = nodeConfig.getBoolean("publishOurPeersLocation");
		
		nodeConfig.register("routeAccordingToOurPeersLocation", false, sortOrder++, true, false, "Node.routeAccordingToOurPeersLocation", "Node.routeAccordingToOurPeersLocationLong", new BooleanCallback() {

			public Boolean get() {
				return routeAccordingToOurPeersLocation;
			}

			public void set(Boolean val) throws InvalidConfigValueException {
				routeAccordingToOurPeersLocation = val;
			}
		});
		routeAccordingToOurPeersLocation = nodeConfig.getBoolean("routeAccordingToOurPeersLocation");
		
		nodeConfig.register("enableSwapQueueing", true, sortOrder++, true, false, "Node.enableSwapQueueing", "Node.enableSwapQueueingLong", new BooleanCallback() {
			public Boolean get() {
				return enableSwapQueueing;
			}

			public void set(Boolean val) throws InvalidConfigValueException {
				enableSwapQueueing = val;
			}
			
		});
		enableSwapQueueing = nodeConfig.getBoolean("enableSwapQueueing");
		
		nodeConfig.register("enablePacketCoalescing", true, sortOrder++, true, false, "Node.enablePacketCoalescing", "Node.enablePacketCoalescingLong", new BooleanCallback() {
			public Boolean get() {
				return enablePacketCoalescing;
			}

			public void set(Boolean val) throws InvalidConfigValueException {
				enablePacketCoalescing = val;
			}
			
		});
		enablePacketCoalescing = nodeConfig.getBoolean("enablePacketCoalescing");
		
		// Determine the port number
		// @see #191
		if(oldConfig != null && "-1".equals(oldConfig.get("node.listenPort")))
			throw new NodeInitException(NodeInitException.EXIT_COULD_NOT_BIND_USM, "Your freenet.ini file is corrupted! 'listenPort=-1'");
		NodeCryptoConfig darknetConfig = new NodeCryptoConfig(nodeConfig, sortOrder++, false);
		sortOrder += NodeCryptoConfig.OPTION_COUNT;
		
		darknetCrypto = new NodeCrypto(this, false, darknetConfig, startupTime, enableARKs);

		// Must be created after darknetCrypto
		dnsr = new DNSRequester(this);
		ps = new PacketSender(this);
		if(executor instanceof PooledExecutor)
			((PooledExecutor)executor).setTicker(ps);
		
		Logger.normal(Node.class, "Creating node...");

		// init shutdown hook
		shutdownHook = new SemiOrderedShutdownHook();
		Runtime.getRuntime().addShutdownHook(shutdownHook);

		shutdownHook.addEarlyJob(new Thread() {
			public void run() {
				if (opennet != null)
					opennet.stop(false);
			}
		});

		shutdownHook.addEarlyJob(new Thread() {
			public void run() {
				darknetCrypto.stop();
			}
		});
		
		// Bandwidth limit

		nodeConfig.register("outputBandwidthLimit", "15K", sortOrder++, false, true, "Node.outBWLimit", "Node.outBWLimitLong", new IntCallback() {
					public Integer get() {
						//return BlockTransmitter.getHardBandwidthLimit();
						return outputBandwidthLimit;
					}
					public void set(Integer obwLimit) throws InvalidConfigValueException {
						if(obwLimit <= 0) throw new InvalidConfigValueException(l10n("bwlimitMustBePositive"));
						synchronized(Node.this) {
							outputBandwidthLimit = obwLimit;
						}
						outputThrottle.changeNanosAndBucketSize((1000L * 1000L * 1000L) / obwLimit, obwLimit/2);
						nodeStats.setOutputLimit(obwLimit);
					}
		});
		
		int obwLimit = nodeConfig.getInt("outputBandwidthLimit");
		if(obwLimit <= 0)
			throw new NodeInitException(NodeInitException.EXIT_BAD_BWLIMIT, "Invalid outputBandwidthLimit");
		outputBandwidthLimit = obwLimit;
		// Bucket size of 0.5 seconds' worth of bytes.
		// Add them at a rate determined by the obwLimit.
		// Maximum forced bytes 80%, in other words, 20% of the bandwidth is reserved for 
		// block transfers, so we will use that 20% for block transfers even if more than 80% of the limit is used for non-limited data (resends etc).
		outputThrottle = new DoubleTokenBucket(obwLimit/2, (1000L*1000L*1000L) / obwLimit, obwLimit/2, 0.8);
		
		nodeConfig.register("inputBandwidthLimit", "-1", sortOrder++, false, true, "Node.inBWLimit", "Node.inBWLimitLong",	new IntCallback() {
					public Integer get() {
						if(inputLimitDefault) return -1;
						return inputBandwidthLimit;
					}
					public void set(Integer ibwLimit) throws InvalidConfigValueException {
						synchronized(Node.this) {
							if(ibwLimit == -1) {
								inputLimitDefault = true;
								ibwLimit = outputBandwidthLimit * 4;
							} else {
								if(ibwLimit <= 1) throw new InvalidConfigValueException(l10n("bandwidthLimitMustBePositiveOrMinusOne"));
								inputLimitDefault = false;
							}
							inputBandwidthLimit = ibwLimit;
						}
						nodeStats.setInputLimit(ibwLimit);
					}
		});
		
		int ibwLimit = nodeConfig.getInt("inputBandwidthLimit");
		if(ibwLimit == -1) {
			inputLimitDefault = true;
			ibwLimit = obwLimit * 4;
		} else if(ibwLimit <= 0)
			throw new NodeInitException(NodeInitException.EXIT_BAD_BWLIMIT, "Invalid inputBandwidthLimit");
		inputBandwidthLimit = ibwLimit;
		
		nodeConfig.register("throttleLocalTraffic", false, sortOrder++, true, false, "Node.throttleLocalTraffic", "Node.throttleLocalTrafficLong", new BooleanCallback() {

			public Boolean get() {
				return throttleLocalData;
			}

			public void set(Boolean val) throws InvalidConfigValueException {
				throttleLocalData = val;
			}
			
		});
		
		throttleLocalData = nodeConfig.getBoolean("throttleLocalTraffic");
		
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
					throw new Error("Impossible: " + e, e);
				} catch (NodeNeedRestartException e) {
					throw new Error("Impossible: " + e, e);
				}
			}
		} else {
			String s = "Testnet mode DISABLED. You may have some level of anonymity. :)\n"+
				"Note that this version of Freenet is still a very early alpha, and may well have numerous bugs and design flaws.\n"+
				"In particular: YOU ARE WIDE OPEN TO YOUR IMMEDIATE PEERS! They can eavesdrop on your requests with relatively little difficulty at present (correlation attacks etc).";
			Logger.normal(this, s);
			System.err.println(s);
			testnetEnabled = false;
			if(wasTestnet) {
				FileLoggerHook flh = logConfigHandler.getFileLoggerHook();
				if(flh != null) flh.deleteAllOldLogFiles();
			}
		}
		
		File nodeFile = new File(nodeDir, "node-"+getDarknetPortNumber());
		File nodeFileBackup = new File(nodeDir, "node-"+getDarknetPortNumber()+".bak");
		// After we have set up testnet and IP address, load the node file
		try {
			// FIXME should take file directly?
			readNodeFile(nodeFile.getPath(), random);
		} catch (IOException e) {
			try {
				System.err.println("Trying to read node file backup ...");
				readNodeFile(nodeFileBackup.getPath(), random);
			} catch (IOException e1) {
				if(nodeFile.exists() || nodeFileBackup.exists()) {
					System.err.println("No node file or cannot read, (re)initialising crypto etc");
					System.err.println(e1.toString());
					e1.printStackTrace();
					System.err.println("After:");
					System.err.println(e.toString());
					e.printStackTrace();
				} else {
					System.err.println("Creating new cryptographic keys...");
				}
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
		peers.tryReadPeers(new File(nodeDir, "peers-"+getDarknetPortNumber()).getPath(), darknetCrypto, null, false, false);
		peers.writePeers();
		peers.updatePMUserAlert();

		uptime = new UptimeEstimator(nodeDir, ps, darknetCrypto.identityHash);
		
		// ULPRs
		
		failureTable = new FailureTable(this);
		
		// Opennet
		
		final SubConfig opennetConfig = new SubConfig("node.opennet", config);
		opennetConfig.register("connectToSeednodes", true, 0, true, false, "Node.withAnnouncement", "Node.withAnnouncementLong", new BooleanCallback() {
			public Boolean get() {
				return isAllowedToConnectToSeednodes;
			}
			public void set(Boolean val) throws InvalidConfigValueException {
				if(val == get()) return;
				synchronized(Node.this) {
					if(opennet != null)
						throw new InvalidConfigValueException("Can't change that setting on the fly when opennet is already active!");
					else
						isAllowedToConnectToSeednodes = val;
				}
			}

			public boolean isReadOnly() {
				        return opennet != null;
			        }
		});
		isAllowedToConnectToSeednodes = opennetConfig.getBoolean("connectToSeednodes");
		
		// Can be enabled on the fly
		opennetConfig.register("enabled", false, 0, false, true, "Node.opennetEnabled", "Node.opennetEnabledLong", new BooleanCallback() {
			public Boolean get() {
				synchronized(Node.this) {
					return opennet != null;
				}
			}
			public void set(Boolean val) throws InvalidConfigValueException {
				OpennetManager o;
				synchronized(Node.this) {
					if(val == (opennet != null)) return;
					if(val) {
						try {
							o = opennet = new OpennetManager(Node.this, opennetCryptoConfig, System.currentTimeMillis(), isAllowedToConnectToSeednodes);
						} catch (NodeInitException e) {
							opennet = null;
							throw new InvalidConfigValueException(e.getMessage());
						}
					} else {
						o = opennet;
						opennet = null;
					}
				}
				if(val) o.start();
				else o.stop(true);
				ipDetector.ipDetectorManager.notifyPortChange(getPublicInterfacePorts());
			}
		});		
		boolean opennetEnabled = opennetConfig.getBoolean("enabled");
		
		opennetConfig.register("maxOpennetPeers", "20", 1, true, false, "Node.maxOpennetPeers",
				"Node.maxOpennetPeersLong", new IntCallback() {
					public Integer get() {
						return maxOpennetPeers;
					}
					public void set(Integer inputMaxOpennetPeers) throws InvalidConfigValueException {
						if(inputMaxOpennetPeers < 0) throw new InvalidConfigValueException(l10n("mustBePositive"));
						if(inputMaxOpennetPeers > 20) throw new InvalidConfigValueException(l10n("maxOpennetPeersMustBeTwentyOrLess"));
						maxOpennetPeers = inputMaxOpennetPeers;
						}
					}
		);
		
		maxOpennetPeers = opennetConfig.getInt("maxOpennetPeers");
		if(maxOpennetPeers > 20) {
			Logger.error(this, "maxOpennetPeers may not be over 20");
			maxOpennetPeers = 20;
		}
		
		opennetCryptoConfig = new NodeCryptoConfig(opennetConfig, 2 /* 0 = enabled */, true);
		
		if(opennetEnabled) {
			opennet = new OpennetManager(this, opennetCryptoConfig, System.currentTimeMillis(), isAllowedToConnectToSeednodes);
			// Will be started later
		} else {
			opennet = null;
		}
		
		opennetConfig.register("acceptSeedConnections", true, 2, true, true, "Node.acceptSeedConnectionsShort", "Node.acceptSeedConnections", new BooleanCallback() {

			public Boolean get() {
				return acceptSeedConnections;
			}

			public void set(Boolean val) throws InvalidConfigValueException {
				acceptSeedConnections = val;
			}
			
		});
		
		acceptSeedConnections = opennetConfig.getBoolean("acceptSeedConnections");
		
		opennetConfig.finishedInitialization();
		
		nodeConfig.register("passOpennetPeersThroughDarknet", true, sortOrder++, true, false, "Node.passOpennetPeersThroughDarknet", "Node.passOpennetPeersThroughDarknetLong",
				new BooleanCallback() {

					public Boolean get() {
						synchronized(Node.this) {
							return passOpennetRefsThroughDarknet;
						}
					}

					public void set(Boolean val) throws InvalidConfigValueException {
						synchronized(Node.this) {
							passOpennetRefsThroughDarknet = val;
						}
					}
			
		});

		passOpennetRefsThroughDarknet = nodeConfig.getBoolean("passOpennetPeersThroughDarknet");
		
		// Extra Peer Data Directory
		nodeConfig.register("extraPeerDataDir", new File(nodeDir, "extra-peer-data-"+getDarknetPortNumber()).toString(), sortOrder++, true, true /* can't be changed on the fly, also for packages */, "Node.extraPeerDir", "Node.extraPeerDirLong",
				new StringCallback() {
					public String get() {
						return extraPeerDataDir.getPath();
					}
					public void set(String val) throws InvalidConfigValueException {
						if(extraPeerDataDir.equals(new File(val))) return;
						// FIXME
						throw new InvalidConfigValueException("Moving extra peer data directory on the fly not supported at present");
					}
					public boolean isReadOnly() {
				        return true;
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

					public Boolean get() {
						synchronized(Node.this) {
							return storeForceBigShrinks;
						}
					}

					public void set(Boolean val) throws InvalidConfigValueException {
						synchronized(Node.this) {
							storeForceBigShrinks = val;
						}
					}
			
		});
		
		nodeConfig.register("storeType", "bdb-index", sortOrder++, true, false, "Node.storeType", "Node.storeTypeLong", new StoreTypeCallback());
		
		storeType = nodeConfig.getString("storeType");
		
		nodeConfig.register("storeSize", "1G", sortOrder++, false, true, "Node.storeSize", "Node.storeSizeLong", 
				new LongCallback() {

					public Long get() {
						return maxTotalDatastoreSize;
					}

					public void set(Long storeSize) throws InvalidConfigValueException {
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
						//Perhaps a bit hackish...? Seems like this should be near it's definition in NodeStats.
						nodeStats.avgStoreLocation.changeMaxReports((int)maxStoreKeys);
						nodeStats.avgCacheLocation.changeMaxReports((int)maxCacheKeys);
					}
		});
		
		maxTotalDatastoreSize = nodeConfig.getLong("storeSize");
		
		if(maxTotalDatastoreSize < 0 || maxTotalDatastoreSize < (32 * 1024 * 1024) && !storeType.equals("ram")) { // totally arbitrary minimum!
			throw new NodeInitException(NodeInitException.EXIT_INVALID_STORE_SIZE, "Invalid store size");
		}

		maxTotalKeys = maxTotalDatastoreSize / sizePerKey;
		
		nodeConfig.register("storeDir", "datastore", sortOrder++, true, true, "Node.storeDirectory", "Node.storeDirectoryLong", 
				new StringCallback() {
					public String get() {
						return storeDir.getPath();
					}
					public void set(String val) throws InvalidConfigValueException {
						if(storeDir.equals(new File(val))) return;
						// FIXME
						throw new InvalidConfigValueException("Moving datastore on the fly not supported at present");
					}
					public boolean isReadOnly() {
				        return true;
			        }
		});

		final String suffix = "-" + getDarknetPortNumber();
		String datastoreDir = nodeConfig.getString("storeDir");
		// FIXME: temporary cludge for backward compat.
		File tmpFile = new File("datastore");
		if(".".equals(datastoreDir) && !tmpFile.exists()) {
			System.out.println("Your node seems to be using the old directory, we will move it: !!DO NOT RESTART!!");
			Logger.normal(this, "Your node seems to be using the old directory, we will move it: !!DO NOT RESTART!!");
			boolean done = false;
			try {
				if(tmpFile.mkdir()) {
					File chkStoreCache = new File("chk"+suffix+".cache");
					File chkStoreCacheNew = new File("datastore/chk"+suffix+".cache");
					if(!chkStoreCache.renameTo(chkStoreCacheNew))
						throw new IOException();
					File chkStoreStore = new File("chk"+suffix+".store");
					File chkStoreStoreNew = new File("datastore/chk"+suffix+".store");
					if(!chkStoreStore.renameTo(chkStoreStoreNew))
						throw new IOException();
					
					File sskStoreCache = new File("ssk"+suffix+".cache");
					File sskStoreCacheNew = new File("datastore/ssk"+suffix+".cache");
					if(!sskStoreCache.renameTo(sskStoreCacheNew))
						throw new IOException();
					File sskStoreStore = new File("ssk"+suffix+".store");
					File sskStoreStoreNew = new File("datastore/ssk"+suffix+".store");
					if(!sskStoreStore.renameTo(sskStoreStoreNew))
						throw new IOException();
					
					File pubkeyStoreCache = new File("pubkey"+suffix+".cache");
					File pubkeyStoreCacheNew = new File("datastore/pubkey"+suffix+".cache");
					if(!pubkeyStoreCache.renameTo(pubkeyStoreCacheNew))
						throw new IOException();
					File pubkeyStoreStore = new File("pubkey"+suffix+".store");
					File pubkeyStoreStoreNew = new File("datastore/pubkey"+suffix+".store");
					if(!pubkeyStoreStore.renameTo(pubkeyStoreStoreNew))
						throw new IOException();
					
					File databaseStoreDir = new File("database"+suffix);
					File databaseStoreDirNew = new File("datastore/database"+suffix);
					if(!databaseStoreDir.renameTo(databaseStoreDirNew))
						throw new IOException();
					done = true;
				}
			} catch (Throwable e) {
				e.printStackTrace();
				done = false;
			}
			
			if(done) {
				datastoreDir = "datastore/";
				nodeConfig.fixOldDefault("storeDir", datastoreDir);
				Logger.normal(this, "The migration is complete, cool :)");
				System.out.println("The migration is complete, cool :)");
			} else {
				Logger.error(this, "Something went wrong :( please report the bug!");
				System.err.println("Something went wrong :( please report the bug!");
			}
		}
		
		storeDir = new File(datastoreDir);
		if(!((storeDir.exists() && storeDir.isDirectory()) || (storeDir.mkdir()))) {
			String msg = "Could not find or create datastore directory";
			throw new NodeInitException(NodeInitException.EXIT_STORE_OTHER, msg);
		}

		maxStoreKeys = maxTotalKeys / 2;
		maxCacheKeys = maxTotalKeys - maxStoreKeys;
		
		if(storeType.equals("bdb-index")) {
		// Setup datastores
		
		EnvironmentConfig envConfig = BerkeleyDBFreenetStore.getBDBConfig();
		
		File dbDir = new File(storeDir, "database-"+getDarknetPortNumber());
		dbDir.mkdirs();
		
		File reconstructFile = new File(dbDir, "reconstruct");
		
		Environment env = null;
		EnvironmentMutableConfig mutableConfig;
		
		// This can take some time
		System.out.println("Starting database...");
		try {
			if(reconstructFile.exists()) {
				reconstructFile.delete();
				throw new DatabaseException();
			}
			// Auto-recovery can take a long time
			WrapperManager.signalStarting(60*60*1000);
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
		
		shutdownHook.addLateJob(new Thread() {
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

			public Long get() {
				return databaseMaxMemory;
			}

			public void set(Long val) throws InvalidConfigValueException {
				if(val < 0)
					throw new InvalidConfigValueException(l10n("mustBePositive"));
				else {
					long maxHeapMemory = Runtime.getRuntime().maxMemory();
					/* There are some JVMs (for example libgcj 4.1.1) whose Runtime.maxMemory() does not work. */
					if(maxHeapMemory < Long.MAX_VALUE && val > (80 * maxHeapMemory / 100))
						throw new InvalidConfigValueException(l10n("storeMaxMemTooHigh"));
				}
				
				envMutableConfig.setCacheSize(val);
				try{
					storeEnvironment.setMutableConfig(envMutableConfig);
				} catch (DatabaseException e) {
					throw new InvalidConfigValueException(l10n("errorApplyingConfig", "error", e.getLocalizedMessage()));
				}
				databaseMaxMemory = val;
			}
			
		});

		/* There are some JVMs (for example libgcj 4.1.1) whose Runtime.maxMemory() does not work. */
		long maxHeapMemory = Runtime.getRuntime().maxMemory();
		databaseMaxMemory = nodeConfig.getLong("databaseMaxMemory");
		// see #1202
		if(maxHeapMemory < Long.MAX_VALUE && databaseMaxMemory > (80 * maxHeapMemory / 100)){
			Logger.error(this, "The databaseMemory setting is set too high " + databaseMaxMemory +
					" ... let's assume it's not what the user wants to do and restore the default.");
			databaseMaxMemory = Long.valueOf(((LongOption) nodeConfig.getOption("databaseMaxMemory")).getDefault()).longValue();
		}
		envMutableConfig.setCacheSize(databaseMaxMemory);
		// http://www.oracle.com/technology/products/berkeley-db/faq/je_faq.html#35
		
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
			chkDatastore = new CHKStore();
			BerkeleyDBFreenetStore.construct(storeDir, true, suffix, maxStoreKeys, FreenetStore.TYPE_CHK, 
					storeEnvironment, shutdownHook, reconstructFile, chkDatastore, random);
			Logger.normal(this, "Initializing CHK Datacache");
			System.out.println("Initializing CHK Datacache ("+maxCacheKeys+ ':' +maxCacheKeys+" keys)");
			chkDatacache = new CHKStore();
			BerkeleyDBFreenetStore.construct(storeDir, false, suffix, maxCacheKeys, FreenetStore.TYPE_CHK, 
					storeEnvironment, shutdownHook, reconstructFile, chkDatacache, random);
			Logger.normal(this, "Initializing pubKey Datastore");
			System.out.println("Initializing pubKey Datastore");
			pubKeyDatastore = new PubkeyStore();
			BerkeleyDBFreenetStore.construct(storeDir, true, suffix, maxStoreKeys, FreenetStore.TYPE_PUBKEY, 
					storeEnvironment, shutdownHook, reconstructFile, pubKeyDatastore, random);
			Logger.normal(this, "Initializing pubKey Datacache");
			System.out.println("Initializing pubKey Datacache ("+maxCacheKeys+" keys)");
			pubKeyDatacache = new PubkeyStore();
			BerkeleyDBFreenetStore.construct(storeDir, false, suffix, maxCacheKeys, FreenetStore.TYPE_PUBKEY, 
					storeEnvironment, shutdownHook, reconstructFile, pubKeyDatacache, random);
			// FIXME can't auto-fix SSK stores.
			Logger.normal(this, "Initializing SSK Datastore");
			System.out.println("Initializing SSK Datastore");
			sskDatastore = new SSKStore(this);
			BerkeleyDBFreenetStore.construct(storeDir, true, suffix, maxStoreKeys, FreenetStore.TYPE_SSK, 
					storeEnvironment, shutdownHook, reconstructFile, sskDatastore, random);
			Logger.normal(this, "Initializing SSK Datacache");
			System.out.println("Initializing SSK Datacache ("+maxCacheKeys+" keys)");
			sskDatacache = new SSKStore(this);
			BerkeleyDBFreenetStore.construct(storeDir, false, suffix, maxStoreKeys, FreenetStore.TYPE_SSK, 
					storeEnvironment, shutdownHook, reconstructFile, sskDatacache, random);
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

		} else {
			chkDatastore = new CHKStore();
			new RAMFreenetStore(chkDatastore, (int) Math.min(Integer.MAX_VALUE, maxStoreKeys));
			chkDatacache = new CHKStore();
			new RAMFreenetStore(chkDatacache, (int) Math.min(Integer.MAX_VALUE, maxCacheKeys));
			pubKeyDatastore = new PubkeyStore();
			new RAMFreenetStore(pubKeyDatastore, (int) Math.min(Integer.MAX_VALUE, maxStoreKeys));
			pubKeyDatacache = new PubkeyStore();
			new RAMFreenetStore(pubKeyDatacache, (int) Math.min(Integer.MAX_VALUE, maxCacheKeys));
			sskDatastore = new SSKStore(this);
			new RAMFreenetStore(sskDatastore, (int) Math.min(Integer.MAX_VALUE, maxStoreKeys));
			sskDatacache = new SSKStore(this);
			new RAMFreenetStore(sskDatacache, (int) Math.min(Integer.MAX_VALUE, maxCacheKeys));
			envMutableConfig = null;
			this.storeEnvironment = null;
		}
		
		nodeStats = new NodeStats(this, sortOrder, new SubConfig("node.load", config), obwLimit, ibwLimit, nodeDir);
		
		clientCore = new NodeClientCore(this, config, nodeConfig, nodeDir, getDarknetPortNumber(), sortOrder, oldConfig, fproxyConfig, toadlets);

		netid = new NetworkIDManager(this);
		 
		nodeConfig.register("disableHangCheckers", false, sortOrder++, true, false, "Node.disableHangCheckers", "Node.disableHangCheckersLong", new BooleanCallback() {

			public Boolean get() {
				return disableHangCheckers;
			}

			public void set(Boolean val) throws InvalidConfigValueException {
				disableHangCheckers = val;
			}
		});
		
		disableHangCheckers = nodeConfig.getBoolean("disableHangCheckers");
				
		nodeConfig.finishedInitialization();
		writeNodeFile();
		
		// Initialize the plugin manager
		Logger.normal(this, "Initializing Plugin Manager");
		System.out.println("Initializing Plugin Manager");
		pluginManager = new PluginManager(this);

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
		
		dispatcher.start(nodeStats); // must be before usm
		dnsr.start();
		peers.start(); // must be before usm
		nodeStats.start();
		uptime.start();
		failureTable.start();
		
		darknetCrypto.start(disableHangCheckers);
		if(opennet != null)
			opennet.start();
		ps.start(nodeStats);
		usm.start(ps);
		
		if(isUsingWrapper()) {
			Logger.normal(this, "Using wrapper correctly: "+nodeStarter);
			System.out.println("Using wrapper correctly: "+nodeStarter);
		} else {
			Logger.error(this, "NOT using wrapper (at least not correctly).  Your freenet-ext.jar <http://downloads.freenetproject.org/alpha/freenet-ext.jar> and/or wrapper.conf <https://emu.freenetproject.org/svn/trunk/apps/installer/installclasspath/config/wrapper.conf> need to be updated.");
			System.out.println("NOT using wrapper (at least not correctly).  Your freenet-ext.jar <http://downloads.freenetproject.org/alpha/freenet-ext.jar> and/or wrapper.conf <https://emu.freenetproject.org/svn/trunk/apps/installer/installclasspath/config/wrapper.conf> need to be updated.");
		}
		Logger.normal(this, "Freenet 0.7 Build #"+Version.buildNumber()+" r"+Version.cvsRevision);
		System.out.println("Freenet 0.7 Build #"+Version.buildNumber()+" r"+Version.cvsRevision);
		Logger.normal(this, "FNP port is on "+darknetCrypto.getBindTo()+ ':' +getDarknetPortNumber());
		System.out.println("FNP port is on "+darknetCrypto.getBindTo()+ ':' +getDarknetPortNumber());
		// Start services
		
//		SubConfig pluginManagerConfig = new SubConfig("pluginmanager3", config);
//		pluginManager3 = new freenet.plugin_new.PluginManager(pluginManagerConfig);
		
		ipDetector.start();
		
		// Start sending swaps
		lm.startSender();

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
		
		/* TODO: Make sure that this is called BEFORE any instances of HTTPFilter are created.
		 * HTTPFilter uses checkForGCJCharConversionBug() which returns the value of the static
		 * variable jvmHasGCJCharConversionBug - and this is initialized in the following function.
		 * If this is not possible then create a separate function to check for the GCJ bug and
		 * call this function earlier.
		 */ 
		checkForEvilJVMBugs();
		
		// TODO: implement a "required" version if needed
		if(!nodeUpdater.isEnabled() && (NodeStarter.RECOMMENDED_EXT_BUILD_NUMBER > NodeStarter.extBuildNumber))
			clientCore.alerts.register(new ExtOldAgeUserAlert());
		else if(NodeStarter.extBuildNumber == -1)
			clientCore.alerts.register(new ExtOldAgeUserAlert());
		
		if(!NativeThread.HAS_ENOUGH_NICE_LEVELS)
			clientCore.alerts.register(new NotEnoughNiceLevelsUserAlert());
		
		clientCore.alerts.register(new OpennetUserAlert(this));
		
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
	
	private static boolean jvmHasGCJCharConversionBug=false;
	
	private void checkForEvilJVMBugs() {
		// Now check whether we are likely to get the EvilJVMBug.
		// If we are running a Sun or Blackdown JVM, on Linux, and LD_ASSUME_KERNEL is not set, then we are.
		
		String jvmVendor = System.getProperty("java.vm.vendor");
		String jvmVersion = System.getProperty("java.version");
		String osName = System.getProperty("os.name");
		String osVersion = System.getProperty("os.version");
		
		if(logMINOR) Logger.minor(this, "JVM vendor: "+jvmVendor+", JVM version: "+jvmVersion+", OS name: "+osName+", OS version: "+osVersion);
		
		if(jvmVersion.startsWith("1.4")) {
			System.err.println("Java 1.4 will not be supported for much longer, PLEASE UPGRADE!");
			nodeUpdater.disableThisSession();
			clientCore.alerts.register(new SimpleUserAlert(false, l10n("java14Title"), l10n("java14Text"), l10n("java14ShortText"), UserAlert.ERROR));
		}
		
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
				clientCore.alerts.register(new AbstractUserAlert(false, null, null, null, null, UserAlert.ERROR, true, null, false, null) {

					public HTMLNode getHTMLText() {
						HTMLNode n = new HTMLNode("div");
						L10n.addL10nSubstitution(n, "Node.buggyJVMWithLink", 
								new String[] { "link", "/link", "version" },
								new String[] { "<a href=\"/?_CHECKED_HTTP_=http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=4855795\">", 
								"</a>", HTMLEncoder.encode(System.getProperty("java.version")) });
						return n;
					}

					public String getText() {
						return l10n("buggyJVM", "version", System.getProperty("java.version"));
					}

					public String getTitle() {
						return l10n("buggyJVMTitle");
					}

					public void isValid(boolean validity) {
						// Ignore
					}

					public String getShortText() {
						return l10n("buggyJVMShort", "version", System.getProperty("java.version"));
					}

				});
			}
		
		} else if (jvmVendor.startsWith("Apple ") || jvmVendor.startsWith("\"Apple ")) {
			//Note that Sun does not produce VMs for the Macintosh operating system, dont ask the user to find one...
		} else {
			if(jvmVendor.startsWith("Free Software Foundation")) {
				try {
					jvmVersion = System.getProperty("java.version").split(" ")[0].replaceAll("[.]","");
					int jvmVersionInt = Integer.parseInt(jvmVersion);
						
					if(jvmVersionInt <= 422 && jvmVersionInt >= 100) // make sure that no bogus values cause true
						jvmHasGCJCharConversionBug=true;
				}
				
				catch(Throwable t) {
					Logger.error(this, "GCJ version check is broken!", t);
				}
			}

			clientCore.alerts.register(new SimpleUserAlert(true, l10n("notUsingSunVMTitle"), l10n("notUsingSunVM", new String[] { "vendor", "version" }, new String[] { jvmVendor, jvmVersion }), l10n("notUsingSunVMShort"), UserAlert.WARNING));
		}
			
		if(!isUsingWrapper()) {
			clientCore.alerts.register(new SimpleUserAlert(true, l10n("notUsingWrapperTitle"), l10n("notUsingWrapper"), l10n("notUsingWrapperShort"), UserAlert.WARNING));
		}
		
	}

	public static boolean checkForGCJCharConversionBug() {	
		return jvmHasGCJCharConversionBug; // should be initialized on early startup
	}

	private String l10n(String key) {
		return L10n.getString("Node."+key);
	}

	private String l10n(String key, String pattern, String value) {
		return L10n.getString("Node."+key, pattern, value);
	}

	private String l10n(String key, String[] pattern, String[] value) {
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
	public int routedPing(double loc2, byte[] nodeIdentity) {
		long uid = random.nextLong();
		int initialX = random.nextInt();
		Message m = DMT.createFNPRoutedPing(uid, loc2, maxHTL, initialX, nodeIdentity);
		Logger.normal(this, "Message: "+m);
		
		dispatcher.handleRouted(m, null);
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
	public Object makeRequestSender(Key key, short htl, long uid, PeerNode source, boolean localOnly, boolean cache, boolean ignoreStore, boolean offersOnly) {
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
			
			// No request coalescing.
			// Given that HTL can be reset (also if we had no HTL),
			// request coalescing causes deadlocks: Request A joins request B, which then
			// joins request A. There are various convoluted fixes, but IMHO
			// the best solution long term is to kill the request (RecentlyFailed with 
			// 0 timeout so it doesn't prevent future requests), and send it the data 
			// through ULPRs if it is found.
			
			sender = new RequestSender(key, null, htl, uid, this, source, offersOnly);
			// RequestSender adds itself to requestSenders
		}
		sender.start();
		if(logMINOR) Logger.minor(this, "Created new sender: "+sender);
		return sender;
	}
	
	static class KeyHTLPair {
		final Key key;
		final short htl;
		final long uid;
		KeyHTLPair(Key key, short htl, long uid) {
			this.key = key;
			this.htl = htl;
			this.uid = uid;
		}
		
		public boolean equals(Object o) {
			if(o instanceof KeyHTLPair) {
				KeyHTLPair p = (KeyHTLPair) o;
				return (p.key.equals(key) && (p.htl == htl) && (p.uid==uid));
			} else return false;
		}
		
		public int hashCode() {
			return key.hashCode() ^ htl ^ (int)uid;
		}
		
		public String toString() {
			return key.toString()+ ':' +htl +':'+uid;
		}
	}

	/**
	 * Add a RequestSender to our HashMap.
	 */
	public void addRequestSender(Key key, short htl, RequestSender sender) {
		synchronized(requestSenders) {
			KeyHTLPair kh = new KeyHTLPair(key, htl, sender.uid);
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
			KeyHTLPair kh = new KeyHTLPair(key, htl, sender.getUID());
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
		synchronized(transferringRequestHandlers) {
			transferringRequestHandlers.add(id);
		}
	}
	
	void removeTransferringRequestHandler(long id) {
		synchronized(transferringRequestHandlers) {
			transferringRequestHandlers.remove(id);
		}
	}

	public KeyBlock fetch(Key key, boolean dontPromote) {
		if(key instanceof NodeSSK)
			return fetch((NodeSSK)key, dontPromote);
		else if(key instanceof NodeCHK)
			return fetch((NodeCHK)key, dontPromote);
		else throw new IllegalArgumentException();
	}
	
	public SSKBlock fetch(NodeSSK key, boolean dontPromote) {
		if(logMINOR) dumpStoreHits();
		try {
			double loc=key.toNormalizedDouble();
			double dist=Location.distance(lm.getLocation(), loc);
			nodeStats.avgRequestLocation.report(loc);
			SSKBlock block = sskDatastore.fetch(key, dontPromote);
			if(block != null) {
				nodeStats.avgStoreSuccess.report(loc);
				if (dist > nodeStats.furthestStoreSuccess)
					nodeStats.furthestStoreSuccess=dist;
				return block;
			}
			block=sskDatacache.fetch(key, dontPromote);
			if (block != null) {
				nodeStats.avgCacheSuccess.report(loc);
				if (dist > nodeStats.furthestCacheSuccess)
					nodeStats.furthestCacheSuccess=dist;
			}
			return block;
		} catch (IOException e) {
			Logger.error(this, "Cannot fetch data: "+e, e);
			return null;
		}
	}

	public CHKBlock fetch(NodeCHK key, boolean dontPromote) {
		if(logMINOR) dumpStoreHits();
		try {
			double loc=key.toNormalizedDouble();
			double dist=Location.distance(lm.getLocation(), loc);
			nodeStats.avgRequestLocation.report(loc);
			CHKBlock block = chkDatastore.fetch(key, dontPromote);
			if (block != null) {
				nodeStats.avgStoreSuccess.report(loc);
				if (dist > nodeStats.furthestStoreSuccess)
					nodeStats.furthestStoreSuccess=dist;
				return block;
			}
			block=chkDatacache.fetch(key, dontPromote);
			if (block != null) {
				nodeStats.avgCacheSuccess.report(loc);
				if (dist > nodeStats.furthestCacheSuccess)
					nodeStats.furthestCacheSuccess=dist;
			}
			return block;
		} catch (IOException e) {
			Logger.error(this, "Cannot fetch data: "+e, e);
			return null;
		}
	}
	
	public CHKStore getChkDatacache() {
		return chkDatacache;
	}
	public CHKStore getChkDatastore() {
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
		boolean deep = !peers.isCloserLocation(loc, MIN_UPTIME_STORE_KEY);
		store(block, deep);
	}

	public void storeShallow(CHKBlock block) {
		store(block, false);
	}
	
	public void store(KeyBlock block, boolean deep) throws KeyCollisionException {
		if(block instanceof CHKBlock)
			store((CHKBlock)block, deep);
		else if(block instanceof SSKBlock)
			store((SSKBlock)block, deep);
		else throw new IllegalArgumentException("Unknown keytype ");
	}
	
	private void store(CHKBlock block, boolean deep) {
		try {
			double loc=block.getKey().toNormalizedDouble();
			if(deep) {
				chkDatastore.put(block);
				nodeStats.avgStoreLocation.report(loc);
			}
			chkDatacache.put(block);
			nodeStats.avgCacheLocation.report(loc);
			if(clientCore != null && clientCore.requestStarters != null)
				clientCore.requestStarters.chkFetchScheduler.tripPendingKey(block);
			failureTable.onFound(block);
		} catch (IOException e) {
			Logger.error(this, "Cannot store data: "+e, e);
		} catch (OutOfMemoryError e) {
			OOMHandler.handleOOM(e);
		} catch (Throwable t) {
			System.err.println(t);
			t.printStackTrace();
			Logger.error(this, "Caught "+t+" storing data", t);
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
	
	public void store(SSKBlock block, boolean deep) throws KeyCollisionException {
		try {
			// Store the pubkey before storing the data, otherwise we can get a race condition and
			// end up deleting the SSK data.
			cacheKey(((NodeSSK)block.getKey()).getPubKeyHash(), ((NodeSSK)block.getKey()).getPubKey(), deep);
			if(deep) {
				sskDatastore.put(block, false);
			}
			sskDatacache.put(block, false);
			if(clientCore != null && clientCore.requestStarters != null)
				clientCore.requestStarters.sskFetchScheduler.tripPendingKey(block);
			failureTable.onFound(block);
		} catch (IOException e) {
			Logger.error(this, "Cannot store data: "+e, e);
		} catch (OutOfMemoryError e) {
			OOMHandler.handleOOM(e);
		} catch (KeyCollisionException e) {
			throw e;
		} catch (Throwable t) {
			System.err.println(t);
			t.printStackTrace();
			Logger.error(this, "Caught "+t+" storing data", t);
		}
	}
	
	/**
	 * Store a datum.
	 * @param deep If true, insert to the store as well as the cache. Do not set
	 * this to true unless the store results from an insert, and this node is the
	 * closest node to the target; see the description of chkDatastore.
	 */
	public void store(SSKBlock block, double loc) throws KeyCollisionException {
		boolean deep = !peers.isCloserLocation(loc, MIN_UPTIME_STORE_KEY);
		store(block, deep);
	}
	
	/**
	 * Remove a sender from the set of currently transferring senders.
	 */
	public void removeTransferringSender(NodeCHK key, RequestSender sender) {
		synchronized(transferringRequestSenders) {
//			RequestSender rs = (RequestSender) transferringRequestSenders.remove(key);
//			if(rs != sender) {
//				Logger.error(this, "Removed "+rs+" should be "+sender+" for "+key+" in removeTransferringSender");
//			}
			
			// Since there is no request coalescing, we only remove it if it matches,
			// and don't complain if it doesn't.
			if(transferringRequestSenders.get(key) == sender)
				transferringRequestSenders.remove(key);
		}
	}

	/**
	 * Remove a RequestSender from the map.
	 */
	public void removeRequestSender(Key key, short htl, RequestSender sender) {
		synchronized(requestSenders) {
			KeyHTLPair kh = new KeyHTLPair(key, htl, sender.uid);
//			RequestSender rs = (RequestSender) requestSenders.remove(kh);
//			if(rs != sender) {
//				Logger.error(this, "Removed "+rs+" should be "+sender+" for "+key+ ',' +htl+" in removeRequestSender");
//			}
			
			// Since there is no request coalescing, we only remove it if it matches,
			// and don't complain if it doesn't.
			if(requestSenders.get(kh) == sender) {
				requestSenders.remove(kh);
				requestSenders.notifyAll();
			}
		}
	}

	/**
	 * Remove an CHKInsertSender from the map.
	 */
	public void removeInsertSender(Key key, short htl, AnyInsertSender sender) {
		synchronized(insertSenders) {
			KeyHTLPair kh = new KeyHTLPair(key, htl, sender.getUID());
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
		if(htl <= 0) {
			return 0;
		}
		if(htl == maxHTL) {
			if(decrementAtMax || disableProbabilisticHTLs) htl--;
			return htl;
		}
		if(htl == 1) {
			if(decrementAtMin || disableProbabilisticHTLs) htl--;
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
			byte[] headers, PartiallyReceivedBlock prb, boolean fromStore, boolean cache) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "makeInsertSender("+key+ ',' +htl+ ',' +uid+ ',' +source+",...,"+fromStore);
		KeyHTLPair kh = new KeyHTLPair(key, htl, uid);
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
		is = new CHKInsertSender(key, uid, headers, htl, source, this, prb, fromStore);
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
			boolean fromStore, boolean cache) {
		NodeSSK key = (NodeSSK) block.getKey();
		if(key.getPubKey() == null) {
			throw new IllegalArgumentException("No pub key when inserting");
		}
		if(cache)
			cacheKey(key.getPubKeyHash(), key.getPubKey(), !peers.isCloserLocation(block.getKey().toNormalizedDouble(), Node.MIN_UPTIME_STORE_KEY));
		Logger.minor(this, "makeInsertSender("+key+ ',' +htl+ ',' +uid+ ',' +source+",...,"+fromStore);
		KeyHTLPair kh = new KeyHTLPair(key, htl, uid);
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
		is = new SSKInsertSender(block, uid, htl, source, this, fromStore);
		is.start();
		Logger.minor(this, is.toString()+" for "+kh.toString());
		// SSKInsertSender adds itself to insertSenders
		return is;
	}
	
	public boolean lockUID(long uid, boolean ssk, boolean insert, boolean offerReply, boolean local) {
		synchronized(runningUIDs) {
			if(!runningUIDs.add(uid)) {
				// Already present.
				return false;
			}
		}
		// If these are switched around, we must remember to remove from both.
		HashSet set = getUIDTracker(ssk, insert, offerReply, local);
		synchronized(set) {
			if(logMINOR) Logger.minor(this, "Locking "+uid+" ssk="+ssk+" insert="+insert+" offerReply="+offerReply+" local="+local+" size="+set.size());
			set.add(uid);
			if(logMINOR) Logger.minor(this, "Locked "+uid+" ssk="+ssk+" insert="+insert+" offerReply="+offerReply+" local="+local+" size="+set.size());
		}
		return true;
	}
	
	public void unlockUID(long uid, boolean ssk, boolean insert, boolean canFail, boolean offerReply, boolean local) {
		completed(uid);
		HashSet set = getUIDTracker(ssk, insert, offerReply, local);
		synchronized(set) {
			if(logMINOR) Logger.minor(this, "Unlocking "+uid+" ssk="+ssk+" insert="+insert+" offerReply="+offerReply+", local="+local+" size="+set.size());
			set.remove(uid);
			if(logMINOR) Logger.minor(this, "Unlocked "+uid+" ssk="+ssk+" insert="+insert+" offerReply="+offerReply+", local="+local+" size="+set.size());
		}
		synchronized(runningUIDs) {
			if(!runningUIDs.remove(uid) && !canFail)
				throw new IllegalStateException("Could not unlock "+uid+ '!');
		}
	}

	HashSet getUIDTracker(boolean ssk, boolean insert, boolean offerReply, boolean local) {
		if(ssk) {
			if(offerReply)
				return runningSSKOfferReplyUIDs;
			if(!local)
				return insert ? runningSSKPutUIDs : runningSSKGetUIDs;
			else
				return insert ? runningLocalSSKPutUIDs : runningLocalSSKGetUIDs;
		} else {
			if(offerReply)
				return runningCHKOfferReplyUIDs;
			if(!local)
				return insert ? runningCHKPutUIDs : runningCHKGetUIDs;
			else
				return insert ? runningLocalCHKPutUIDs : runningLocalCHKGetUIDs;
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
		AnyInsertSender[] senders;
		synchronized(insertSenders) {
			senders = (AnyInsertSender[]) insertSenders.values().toArray(new AnyInsertSender[insertSenders.size()]);
		}
		int x = senders.length;
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
		return runningSSKGetUIDs.size() + runningLocalSSKGetUIDs.size();
	}
	
	public int getNumCHKRequests() {
		return runningCHKGetUIDs.size() + runningLocalCHKGetUIDs.size();
	}
	
	public int getNumSSKInserts() {
		return runningSSKPutUIDs.size() + runningLocalSSKPutUIDs.size();
	}
	
	public int getNumCHKInserts() {
		return runningCHKPutUIDs.size() + runningLocalCHKPutUIDs.size();
	}
	
	public int getNumLocalSSKRequests() {
		return runningLocalSSKGetUIDs.size();
	}
	
	public int getNumLocalCHKRequests() {
		return runningLocalCHKGetUIDs.size();
	}
	
	public int getNumRemoteSSKRequests() {
		return runningSSKGetUIDs.size();
	}
	
	public int getNumRemoteCHKRequests() {
		return runningCHKGetUIDs.size();
	}
	
	public int getNumLocalSSKInserts() {
		return runningLocalSSKPutUIDs.size();
	}
	
	public int getNumLocalCHKInserts() {
		return runningLocalCHKPutUIDs.size();
	}
	
	public int getNumRemoteSSKInserts() {
		return runningSSKPutUIDs.size();
	}
	
	public int getNumRemoteCHKInserts() {
		return runningCHKPutUIDs.size();
	}
	
	public int getNumSSKOfferReplies() {
		return runningSSKOfferReplyUIDs.size();
	}
	
	public int getNumCHKOfferReplies() {
		return runningCHKOfferReplyUIDs.size();
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
	public boolean recentlyCompleted(long id) {
		synchronized (recentlyCompletedIDs) {
			return recentlyCompletedIDs.contains(id);
		}
	}
	
	/**
	 * A request completed (regardless of success).
	 */
	void completed(long id) {
		synchronized (recentlyCompletedIDs) {
			recentlyCompletedIDs.push(id);
			while(recentlyCompletedIDs.size() > MAX_RECENTLY_COMPLETED_IDS)
				recentlyCompletedIDs.pop();
		}
	}

	/* (non-Javadoc)
	 * @see freenet.node.GetPubkey#getKey(byte[])
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
			key = pubKeyDatastore.fetch(hash, false);
			if(key == null)
				key = pubKeyDatacache.fetch(hash, false);
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
				// FIXME is this test really needed?
				// SHA-256 inside synchronized{} is a bad idea
				MessageDigest md256 = SHA256.getMessageDigest();
				try {
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
				} finally {
					SHA256.returnMessageDigest(md256);
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
				pubKeyDatastore.fetch(hash, true);
			}
			pubKeyDatacache.put(hash, key);
			pubKeyDatacache.fetch(hash, true);
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

	public ClientKeyBlock fetch(ClientSSK clientSSK, boolean dontPromote) throws SSKVerifyException {
		DSAPublicKey key = clientSSK.getPubKey();
		if(key == null) {
			key = getKey(clientSSK.pubKeyHash);
		}
		if(key == null) return null;
		clientSSK.setPublicKey(key);
		SSKBlock block = fetch((NodeSSK)clientSSK.getNodeKey(), dontPromote);
		if(block == null) {
			if(logMINOR)
				Logger.minor(this, "Could not find key for "+clientSSK+" (dontPromote="+dontPromote+")");
			return null;
		}
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
	 * Returns true if the node is shutting down.
	 * The packet receiver calls this for every packet, and boolean is atomic, so this method is not synchronized.
	 */
	public boolean isStopping() {
		return isStopping;
	}
	
	/**
	 * Get the node into a state where it can be stopped safely
	 * May be called twice - once in exit (above) and then again
	 * from the wrapper triggered by calling System.exit(). Beware!
	 */
	public void park() {
		synchronized(this) {
			if(isStopping) return;
			isStopping = true;
		}
		
		try {
			Message msg = DMT.createFNPDisconnect(false, false, -1, new ShortBuffer(new byte[0]));
			peers.localBroadcast(msg, true, false, peers.ctrDisconn);
		} catch (Throwable t) {
			try {
				// E.g. if we haven't finished startup
				Logger.error(this, "Failed to tell peers we are going down: "+t, t);
			} catch (Throwable t1) {
				// Ignore. We don't want to mess up the exit process!
			}
		}
		
		config.store();
		
		// TODO: find a smarter way of doing it not involving any casting
		Yarrow myRandom = (Yarrow) random;
		myRandom.write_seed(myRandom.seedfile, true);
	}

	public NodeUpdateManager getNodeUpdater(){
		return nodeUpdater;
	}
	
	public DarknetPeerNode[] getDarknetConnections() {
		return peers.getDarknetPeers();
	}
	
	public boolean addPeerConnection(PeerNode pn) {
		boolean retval = peers.addPeer(pn);
		peers.writePeers();
		return retval;
	}
	
	public void removePeerConnection(PeerNode pn) {
		peers.disconnect(pn, true, false);
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
	
	public synchronized boolean isOudated() {
		return (buildOldAgeUserAlert.lastGoodVersion > 0);
	}
	
	/**
	 * Handle a received node to node message
	 */
	public void receivedNodeToNodeMessage(Message m, PeerNode src) {
		int type = ((Integer) m.getObject(DMT.NODE_TO_NODE_MESSAGE_TYPE)).intValue();
		ShortBuffer messageData = (ShortBuffer) m.getObject(DMT.NODE_TO_NODE_MESSAGE_DATA);
		receivedNodeToNodeMessage(src, type, messageData, false);
	}
	
	public void receivedNodeToNodeMessage(PeerNode src, int type, ShortBuffer messageData, boolean partingMessage) {
		boolean fromDarknet = false;
		if(src instanceof DarknetPeerNode) {
			fromDarknet = true;
		}
		DarknetPeerNode darkSource = null;
		if(fromDarknet) {
			darkSource = (DarknetPeerNode)src;
		}
		
		if(type == Node.N2N_MESSAGE_TYPE_FPROXY) {
			if(!fromDarknet) {
				Logger.error(this, "Got N2NTM from non-darknet node ?!?!?!: from "+src);
				return;
			}
			Logger.normal(this, "Received N2NTM from '"+darkSource.getPeer()+"'");
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
			int fileNumber = darkSource.writeNewExtraPeerDataFile( fs, EXTRA_PEER_DATA_TYPE_N2NTM);
			if( fileNumber == -1 ) {
				Logger.error( this, "Failed to write N2NTM to extra peer data file for peer "+darkSource.getPeer());
			}
			// Keep track of the fileNumber so we can potentially delete the extra peer data file later, the file is authoritative
			try {
				handleNodeToNodeTextMessageSimpleFieldSet(fs, darkSource, fileNumber);
			} catch (FSParseException e) {
				// Shouldn't happen
				throw new Error(e);
			}
		} else if(type == Node.N2N_MESSAGE_TYPE_DIFFNODEREF) {
			Logger.normal(this, "Received differential node reference node to node message from "+src.getPeer());
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
			try {
				src.processDiffNoderef(fs);
			} catch (FSParseException e) {
				Logger.error(this, "FSParseException while parsing node to node message data", e);
				return;
			}
		} else {
			Logger.error(this, "Received unknown node to node message type '"+type+"' from "+src.getPeer());
		}
	}

	/**
	 * Handle a node to node text message SimpleFieldSet
	 * @throws FSParseException 
	 */
	public void handleNodeToNodeTextMessageSimpleFieldSet(SimpleFieldSet fs, DarknetPeerNode source, int fileNumber) throws FSParseException {
		if(logMINOR)
			Logger.minor(this, "Got node to node message: \n"+fs);
		int overallType = fs.getInt("n2nType");
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

	public LocationManager getLocationManager() {
		return lm;
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
	public PeerNode getPeerNode(String nodeIdentifier) {
		PeerNode[] pn = peers.myPeers;
		for(int i=0;i<pn.length;i++)
		{
			Peer peer = pn[i].getPeer();
			String nodeIpAndPort = "";
			if(peer != null) {
				nodeIpAndPort = peer.toString();
			}
			String identity = pn[i].getIdentityString();
			if(pn[i] instanceof DarknetPeerNode) {
				DarknetPeerNode dpn = (DarknetPeerNode) pn[i];
				String name = dpn.myName;
				if(identity.equals(nodeIdentifier) || nodeIpAndPort.equals(nodeIdentifier) || name.equals(nodeIdentifier)) {
					return pn[i];
				}
			} else {
				if(identity.equals(nodeIdentifier) || nodeIpAndPort.equals(nodeIdentifier)) {
					return pn[i];
				}
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
		return lm.getLocation();
	}

	public double getLocationChangeSession() {
		return lm.getLocChangeSession();
	}
	
	public int getAverageOutgoingSwapTime() {
		return lm.getAverageSwapTime();
	}

	public int getSendSwapInterval() {
		return lm.getSendSwapInterval();
	}
	
	public int getNumberOfRemotePeerLocationsSeenInSwaps() {
		return lm.numberOfRemotePeerLocationsSeenInSwaps;
	}
	
	public boolean isAdvancedModeEnabled() {
		if(clientCore == null) return false;
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

	public void setName(String key) throws InvalidConfigValueException, NodeNeedRestartException {
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
	public void connectToSeednode(SeedServerTestPeerNode node) throws OpennetDisabledException, FSParseException, PeerParseException, ReferenceSignatureVerificationException {
		peers.addPeer(node,false,false);
	}
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
		if (storeEnvironment == null) {
			System.out.println("database stat not availiable");
			return;
		}
		try { 
			StatsConfig statsConf = new StatsConfig();
			statsConf.setClear(true);
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

	public OpennetPeerNode createNewOpennetNode(SimpleFieldSet fs) throws FSParseException, OpennetDisabledException, PeerParseException, ReferenceSignatureVerificationException {
		if(opennet == null) throw new OpennetDisabledException("Opennet is not currently enabled");
		return new OpennetPeerNode(fs, this, opennet.crypto, opennet, peers, false, opennet.crypto.packetMangler);
	}
	
	public SeedServerTestPeerNode createNewSeedServerTestPeerNode(SimpleFieldSet fs) throws FSParseException, OpennetDisabledException, PeerParseException, ReferenceSignatureVerificationException {		
		if(opennet == null) throw new OpennetDisabledException("Opennet is not currently enabled");
		return new SeedServerTestPeerNode(fs, this, opennet.crypto, peers, true, opennet.crypto.packetMangler);
	}
	
	public OpennetPeerNode addNewOpennetNode(SimpleFieldSet fs) throws FSParseException, PeerParseException, ReferenceSignatureVerificationException {
		// FIXME: perhaps this should throw OpennetDisabledExcemption rather than returing false?
		if(opennet == null) return null;
		return opennet.addNewOpennetNode(fs);
	}
	
	public byte[] getOpennetIdentity() {
		return opennet.crypto.myIdentity;
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

	public SimpleFieldSet exportOpennetPrivateFieldSet() {
		return opennet.crypto.exportPrivateFieldSet();
	}

	/**
	 * Should the IP detection code only use the IP address override and the bindTo information,
	 * rather than doing a full detection?
	 */
	public synchronized boolean dontDetect() {
		// Only return true if bindTo is set on all ports which are in use
		if(!darknetCrypto.getBindTo().isRealInternetAddress(false, true, false)) return false;
		if(opennet != null) {
			if(opennet.crypto.getBindTo().isRealInternetAddress(false, true, false)) return false;
		}
		return true;
	}

	public int getOpennetFNPPort() {
		if(opennet == null) return -1;
		return opennet.crypto.portNumber;
	}

	OpennetManager getOpennet() {
		return opennet;
	}
	
	public synchronized boolean passOpennetRefsThroughDarknet() {
		return passOpennetRefsThroughDarknet;
	}

	/**
	 * Get the set of public ports that need to be forwarded. These are internal
	 * ports, not necessarily external - they may be rewritten by the NAT.
	 * @return A Set of ForwardPort's to be fed to port forward plugins.
	 */
	public Set getPublicInterfacePorts() {
		HashSet set = new HashSet();
		// FIXME IPv6 support
		set.add(new ForwardPort("darknet", false, ForwardPort.PROTOCOL_UDP_IPV4, darknetCrypto.portNumber));
		if(opennet != null) {
			NodeCrypto crypto = opennet.crypto;
			if(crypto != null) {
				set.add(new ForwardPort("opennet", false, ForwardPort.PROTOCOL_UDP_IPV4, crypto.portNumber));
			}
		}
		return set;
	}

	public long getUptime() {
		return System.currentTimeMillis() - usm.getStartedTime();
	}

	public synchronized UdpSocketHandler[] getPacketSocketHandlers() {
		// FIXME better way to get these!
		if(opennet != null) {
			return new UdpSocketHandler[] { darknetCrypto.socket, opennet.crypto.socket };
			// TODO Auto-generated method stub
		} else {
			return new UdpSocketHandler[] { darknetCrypto.socket };
		}
	}

	public int getMaxOpennetPeers() {
		return maxOpennetPeers;
	}

	public void onAddedValidIP() {
		OpennetManager om;
		synchronized(this) {
			om = opennet;
		}
		if(om != null) {
			Announcer announcer = om.announcer;
			if(announcer != null) {
				announcer.maybeSendAnnouncement();
			}
		}
	}

	/**
	 * Returns true if the packet receiver should try to decode/process packets that are not from a peer (i.e. from a seed connection)
	 * The packet receiver calls this upon receiving an unrecognized packet.
	 */
	public boolean wantAnonAuth() {
		return opennet != null && acceptSeedConnections;
	}
	
	public void displayClockProblemUserAlert(boolean value) {
		if(value)
			clientCore.alerts.register(clockProblemDetectedUserAlert);
		else
			clientCore.alerts.unregister(clockProblemDetectedUserAlert);
	}

	public boolean opennetDefinitelyPortForwarded() {
		OpennetManager om;
		synchronized(this) {
			om = this.opennet;
		}
		if(om == null) return false;
		NodeCrypto crypto = om.crypto;
		if(crypto == null) return false;
		return crypto.definitelyPortForwarded();
	}
	
	public boolean darknetDefinitelyPortForwarded() {
		if(darknetCrypto == null) return false;
		return darknetCrypto.definitelyPortForwarded();
	}

	public boolean hasKey(Key key) {
		// FIXME optimise!
		if(key instanceof NodeCHK)
			return fetch((NodeCHK)key, true) != null;
		else
			return fetch((NodeSSK)key, true) != null;
	}

	public int getTotalRunningUIDs() {
		synchronized(runningUIDs) {
			return runningUIDs.size();
		}
	}

	public void addRunningUIDs(Vector list) {
		synchronized(runningUIDs) {
			list.addAll(runningUIDs);
		}
	}
	
	public int getTotalRunningUIDsAlt() {
		synchronized(runningUIDs) {
			return this.runningCHKGetUIDs.size() + this.runningCHKPutUIDs.size() + this.runningSSKGetUIDs.size() +
			this.runningSSKGetUIDs.size() + this.runningSSKOfferReplyUIDs.size() + this.runningCHKOfferReplyUIDs.size();
		}
	}

	/**
	 * Warning: does not announce change in location!
	 */
	public void setLocation(double loc) {
		lm.setLocation(loc);
	}

	public boolean peersWantKey(Key key) {
		return failureTable.peersWantKey(key);
	}

	private SimpleUserAlert alertMTUTooSmall;
	
	public void onTooLowMTU(int minAdvertisedMTU, int minAcceptableMTU) {
		if(alertMTUTooSmall == null) {
			alertMTUTooSmall = new SimpleUserAlert(false, l10n("tooSmallMTU"), l10n("tooSmallMTULong", new String[] { "mtu", "minMTU" }, new String[] { Integer.toString(minAdvertisedMTU), Integer.toString(minAcceptableMTU) }), l10n("tooSmallMTUShort"), UserAlert.ERROR);
		} else return;
		clientCore.alerts.register(alertMTUTooSmall);
	}

	public void setDispatcherHook(NodeDispatcherCallback cb) {
		this.dispatcher.setHook(cb);
	}
	
	public boolean shallWePublishOurPeersLocation() {
		return publishOurPeersLocation;
	}
	
	public boolean shallWeRouteAccordingToOurPeersLocation() {
		return routeAccordingToOurPeersLocation;
	}
}
