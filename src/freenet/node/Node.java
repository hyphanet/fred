/*
 * Freenet 0.7 node.
 * 
 * Designed primarily for darknet operation, but should also be usable
 * in open mode eventually.
 */
package freenet.node;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.zip.DeflaterOutputStream;

import freenet.client.ArchiveManager;
import freenet.client.HighLevelSimpleClient;
import freenet.client.HighLevelSimpleClientImpl;
import freenet.client.async.ClientRequestScheduler;
import freenet.client.async.USKManager;
import freenet.clients.http.FproxyToadlet;
import freenet.clients.http.SimpleToadletServer;
import freenet.config.Config;
import freenet.config.FilePersistentConfig;
import freenet.config.IntCallback;
import freenet.config.InvalidConfigValueException;
import freenet.config.LongCallback;
import freenet.config.StringCallback;
import freenet.config.SubConfig;
import freenet.crypt.DSAGroup;
import freenet.crypt.DSAPrivateKey;
import freenet.crypt.DSAPublicKey;
import freenet.crypt.DiffieHellman;
import freenet.crypt.Global;
import freenet.crypt.RandomSource;
import freenet.crypt.Yarrow;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.Peer;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.UdpSocketManager;
import freenet.io.xfer.AbortedException;
import freenet.io.xfer.BlockTransmitter;
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
import freenet.node.fcp.FCPServer;
import freenet.pluginmanager.PluginManager;
import freenet.store.BerkeleyDBFreenetStore;
import freenet.store.FreenetStore;
import freenet.support.Base64;
import freenet.support.BucketFactory;
import freenet.support.Fields;
import freenet.support.FileLoggerHook;
import freenet.support.HexUtil;
import freenet.support.IllegalBase64Exception;
import freenet.support.ImmutableByteArrayWrapper;
import freenet.support.LRUHashtable;
import freenet.support.LRUQueue;
import freenet.support.Logger;
import freenet.support.PaddedEphemerallyEncryptedBucketFactory;
import freenet.support.SimpleFieldSet;
import freenet.support.io.FilenameGenerator;
import freenet.support.io.PersistentTempBucketFactory;
import freenet.support.io.TempBucketFactory;
import freenet.support.math.BootstrappingDecayingRunningAverage;
import freenet.support.math.RunningAverage;
import freenet.support.math.TimeDecayingRunningAverage;
import freenet.transport.IPAddressDetector;
import freenet.transport.IPUtil;

/**
 * @author amphibian
 */
public class Node {

	private static IPUndetectedUserAlert primaryIPUndetectedAlert;
	private static MeaningfulNodeNameUserAlert nodeNameUserAlert;
	
	public class NodeNameCallback implements StringCallback{
			Node node;
		
			NodeNameCallback(Node n) {
				node=n;
			}
			public String get() {
				if(myName.startsWith("Node created around")|| myName.equals("MyFirstFreenetNode")){
		        	node.alerts.register(nodeNameUserAlert);
		        }else{
		        	node.alerts.unregister(nodeNameUserAlert);
		        }
				return myName;
			}

			public void set(String val) throws InvalidConfigValueException {
				myName = val;
				if(myName.startsWith("Node created around")|| myName.equals("MyFirstFreenetNode")){
		        	node.alerts.register(nodeNameUserAlert);
		        }else{
		        	node.alerts.unregister(nodeNameUserAlert);
		        }
			}
	}
	
	public class MyRequestThrottle implements BaseRequestThrottle {

		private final BootstrappingDecayingRunningAverage roundTripTime; 
		
		public MyRequestThrottle(ThrottleWindowManager throttleWindow, int rtt, String string) {
			roundTripTime = new BootstrappingDecayingRunningAverage(rtt, 10, 5*60*1000, 10);
		}

		public synchronized long getDelay() {
			double rtt = roundTripTime.currentValue();
			double winSizeForMinPacketDelay = rtt / MIN_DELAY;
			double _simulatedWindowSize = throttleWindow.currentValue();
			if (_simulatedWindowSize > winSizeForMinPacketDelay) {
				_simulatedWindowSize = winSizeForMinPacketDelay;
			}
			if (_simulatedWindowSize < 1.0) {
				_simulatedWindowSize = 1.0F;
			}
			// return (long) (_roundTripTime / _simulatedWindowSize);
			return Math.max(MIN_DELAY, Math.min((long) (rtt / _simulatedWindowSize), MAX_DELAY));
		}

		public synchronized void successfulCompletion(long rtt) {
			roundTripTime.report(Math.max(rtt, 10));
		}
	}
	
	/** Config object for the whole node. */
	public final Config config;
	
	// Static stuff related to logger
	
	/** Directory to log to */
	static File logDir;
	/** Maximum size of gzipped logfiles */
	static long maxLogSize;
	/** Log config handler */
	static LoggingConfigHandler logConfigHandler;
	
	/** If true, local requests and inserts aren't cached.
	 * This opens up a glaring vulnerability; connected nodes
	 * can then probe the store, and if the node doesn't have the
	 * content, they know for sure that it was a local request.
	 * HOWEVER, if we don't do this, then a non-full seized 
	 * datastore will contain everything requested by the user...
	 * Also, remote probing is possible.
	 * 
	 * So it may be useful on some darknets, and is useful for 
	 * debugging, but in general should be off on opennet and 
	 * most darknets.
	 */
	public static final boolean DONT_CACHE_LOCAL_REQUESTS = true;
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
    public static final int MIN_TIME_BETWEEN_HANDSHAKE_SENDS = HANDSHAKE_TIMEOUT*2; // 10-15 secs
    public static final int RANDOMIZED_TIME_BETWEEN_HANDSHAKE_SENDS = HANDSHAKE_TIMEOUT;
    public static final int MIN_TIME_BETWEEN_VERSION_PROBES = HANDSHAKE_TIMEOUT*4;
    public static final int RANDOMIZED_TIME_BETWEEN_VERSION_PROBES = HANDSHAKE_TIMEOUT*2; // 20-30 secs
    // If we don't receive any packets at all in this period, from any node, tell the user
    public static final long ALARM_TIME = 60*1000;
    /** Sub-max ping time. If ping is greater than this, we reject some requests. */
    public static final long SUB_MAX_PING_TIME = 500;
    /** Maximum overall average ping time. If ping is greater than this,
     * we reject all requests. */
    public static final long MAX_PING_TIME = 1000;
    /** Maximum throttled packet delay. If the throttled packet delay is greater
     * than this, reject all packets. */
    public static final long MAX_THROTTLE_DELAY = 1000;
    /** If the throttled packet delay is less than this, reject no packets; if it's
     * between the two, reject some packets. */
    public static final long SUB_MAX_THROTTLE_DELAY = 2000;
    
    /** Accept one request every 10 seconds regardless, to ensure we update the
     * block send time.
     */
    public static final int MAX_INTERREQUEST_TIME = 10*1000;

    // 900ms
    static final int MIN_INTERVAL_BETWEEN_INCOMING_SWAP_REQUESTS = 900;
    public static final int SYMMETRIC_KEY_LENGTH = 32; // 256 bits - note that this isn't used everywhere to determine it
    /** Minimum space for zipped logfiles on testnet */
	static final long TESTNET_MIN_MAX_ZIPPED_LOGFILES = 512*1024*1024;
	static final String TESTNET_MIN_MAX_ZIPPED_LOGFILES_STRING = "512M";
    
    // FIXME: abstract out address stuff? Possibly to something like NodeReference?
    final int portNumber;

    /** Datastore directory */
    private final File storeDir;

    /** The number of bytes per key total in all the different datastores. All the datastores
     * are always the same size in number of keys. */
    static final int sizePerKey = CHKBlock.DATA_LENGTH + CHKBlock.TOTAL_HEADERS_LENGTH +
		DSAPublicKey.PADDED_SIZE + SSKBlock.DATA_LENGTH + SSKBlock.TOTAL_HEADERS_LENGTH;
    
    /** The maximum number of keys stored in each of the datastores. */
    private long maxStoreKeys;
    
    /** These 3 are private because must be protected by synchronized(this) */
    /** The CHK datastore */
    private final FreenetStore chkDatastore;
    /** The SSK datastore */
    private final FreenetStore sskDatastore;
    /** The store of DSAPublicKeys (by hash) */
    private final FreenetStore pubKeyDatastore;
    /** RequestSender's currently running, by KeyHTLPair */
    private final HashMap requestSenders;
    /** RequestSender's currently transferring, by key */
    private final HashMap transferringRequestSenders;
    /** CHKInsertSender's currently running, by KeyHTLPair */
    private final HashMap insertSenders;
    /** IP address detector */
    private final IPAddressDetector ipDetector;
    /** My crypto group */
    private DSAGroup myCryptoGroup;
    /** My private key */
    private DSAPrivateKey myPrivKey;
    /** My public key */
    private DSAPublicKey myPubKey;
    
    private final HashSet runningUIDs;
    
    byte[] myIdentity; // FIXME: simple identity block; should be unique
    /** Hash of identity. Used as setup key. */
    byte[] identityHash;
    /** Hash of hash of identity i.e. hash of setup key. */
    byte[] identityHashHash; 
    String myName;
    final LocationManager lm;
    final PeerManager peers; // my peers
    /** Directory to put node, peers, etc into */
    final File nodeDir;
    final File tempDir;
    public final RandomSource random; // strong RNG
    final UdpSocketManager usm;
    final FNPPacketMangler packetMangler;
    final PacketSender ps;
    final NodeDispatcher dispatcher;
    final NodePinger nodePinger;
    final FilenameGenerator tempFilenameGenerator;
    static final int MAX_CACHED_KEYS = 1000;
    final LRUHashtable cachedPubKeys;
    final boolean testnetEnabled;
    final TestnetHandler testnetHandler;
    final StaticSwapRequestInterval swapInterval;
    static short MAX_HTL = 10;
    static final int EXIT_STORE_FILE_NOT_FOUND = 1;
    static final int EXIT_STORE_IOEXCEPTION = 2;
    static final int EXIT_STORE_OTHER = 3;
    static final int EXIT_USM_DIED = 4;
    public static final int EXIT_YARROW_INIT_FAILED = 5;
    static final int EXIT_TEMP_INIT_ERROR = 6;
    static final int EXIT_TESTNET_FAILED = 7;
    public static final int EXIT_MAIN_LOOP_LOST = 8;
    public static final int EXIT_COULD_NOT_BIND_USM = 9;
    static final int EXIT_IMPOSSIBLE_USM_PORT = 10;
    static final int EXIT_NO_AVAILABLE_UDP_PORTS = 11;
	public static final int EXIT_TESTNET_DISABLED_NOT_SUPPORTED = 12;
	static final int EXIT_INVALID_STORE_SIZE = 13;
	static final int EXIT_BAD_DOWNLOADS_DIR = 14;
	static final int EXIT_BAD_NODE_DIR = 15;
	static final int EXIT_BAD_TEMP_DIR = 16;
	static final int EXIT_COULD_NOT_START_FCP = 17;
	static final int EXIT_COULD_NOT_START_FPROXY = 18;
	static final int EXIT_COULD_NOT_START_TMCI = 19;
	public static final int EXIT_DATABASE_REQUIRES_RESTART = 20;

    
    
    public final long bootID;
    public final long startupTime;
    
    // Client stuff
    public final USKManager uskManager;
    final ArchiveManager archiveManager;
    public final BucketFactory tempBucketFactory;
    final ThrottleWindowManager throttleWindow;
    final MyRequestThrottle chkRequestThrottle;
    final RequestStarter chkRequestStarter;
    final MyRequestThrottle chkInsertThrottle;
    final RequestStarter chkInsertStarter;
    final MyRequestThrottle sskRequestThrottle;
    final RequestStarter sskRequestStarter;
    final MyRequestThrottle sskInsertThrottle;
    final RequestStarter sskInsertStarter;
	public final UserAlertManager alerts;
	final RunningAverage throttledPacketSendAverage;

    File downloadDir;
    public final ClientRequestScheduler chkFetchScheduler;
    public final ClientRequestScheduler chkPutScheduler;
    public final ClientRequestScheduler sskFetchScheduler;
    public final ClientRequestScheduler sskPutScheduler;
    TextModeClientInterfaceServer tmci;
    TextModeClientInterface directTMCI;
    FCPServer fcpServer;
    FproxyToadlet fproxyServlet;
    SimpleToadletServer toadletContainer;
    
    // Persistent temporary buckets
    public final PersistentTempBucketFactory persistentTempBucketFactory;
    
    // Things that's needed to keep track of
    public final PluginManager pluginManager;
    
    // Client stuff that needs to be configged - FIXME
    static final int MAX_ARCHIVE_HANDLERS = 200; // don't take up much RAM... FIXME
    static final long MAX_CACHED_ARCHIVE_DATA = 32*1024*1024; // make a fixed fraction of the store by default? FIXME
    static final long MAX_ARCHIVE_SIZE = 1024*1024; // ??? FIXME
    static final long MAX_ARCHIVED_FILE_SIZE = 1024*1024; // arbitrary... FIXME
    static final int MAX_CACHED_ELEMENTS = 1024; // equally arbitrary! FIXME hopefully we can cache many of these though

    // Helpers
	public final InetAddress localhostAddress;

	private boolean wasTestnet;
	
    /**
     * Read all storable settings (identity etc) from the node file.
     * @param filename The name of the file to read from.
     */
    private void readNodeFile(String filename, RandomSource r) throws IOException {
    	// REDFLAG: Any way to share this code with NodePeer?
        FileInputStream fis = new FileInputStream(filename);
        InputStreamReader isr = new InputStreamReader(fis);
        BufferedReader br = new BufferedReader(isr);
        SimpleFieldSet fs = new SimpleFieldSet(br, true);
        br.close();
        // Read contents
        String physical = fs.get("physical.udp");
        if(physical != null) {
        	Peer myOldPeer;
        	try {
        		myOldPeer = new Peer(physical);
        	} catch (PeerParseException e) {
        		IOException e1 = new IOException();
        		e1.initCause(e);
        		throw e1;
        	}
        	if(myOldPeer.getPort() != portNumber)
        		throw new IllegalArgumentException("Wrong port number "+
        				myOldPeer.getPort()+" should be "+portNumber);
        	oldIPAddress = myOldPeer.getAddress();
        }
        String identity = fs.get("identity");
        if(identity == null)
            throw new IOException();
        boolean base64 = Fields.stringToBool(fs.get("base64"), false);
        try {
			myIdentity = base64 ? Base64.decode(identity) : HexUtil.hexToBytes(identity);
		} catch (IllegalBase64Exception e2) {
			throw new IOException();
		}
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new Error(e);
        }
        identityHash = md.digest(myIdentity);
        identityHashHash = md.digest(identityHash);
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

        // FIXME: Back compatibility; REMOVE !!
        try {
        	this.myCryptoGroup = DSAGroup.create(fs.subset("dsaGroup"), base64);
        	this.myPrivKey = DSAPrivateKey.create(fs.subset("dsaPrivKey"), myCryptoGroup, base64);
        	this.myPubKey = DSAPublicKey.create(fs.subset("dsaPubKey"), myCryptoGroup, base64);
        } catch (NullPointerException e) {
        	Logger.minor(this, "Caught "+e, e);
            this.myCryptoGroup = Global.DSAgroupBigA;
            this.myPrivKey = new DSAPrivateKey(myCryptoGroup, r);
            this.myPubKey = new DSAPublicKey(myCryptoGroup, myPrivKey);
        } catch (IllegalBase64Exception e) {
        	Logger.minor(this, "Caught "+e, e);
            this.myCryptoGroup = Global.DSAgroupBigA;
            this.myPrivKey = new DSAPrivateKey(myCryptoGroup, r);
            this.myPubKey = new DSAPublicKey(myCryptoGroup, myPrivKey);
		}
        wasTestnet = Fields.stringToBool(fs.get("testnet"), false);
    }

    private String newName() {
        return "Node created around "+System.currentTimeMillis();
    }

    public void writeNodeFile() {
        try {
            writeNodeFile(new File(nodeDir, "node-"+portNumber), new File(nodeDir, "node-"+portNumber+".bak"));
        } catch (IOException e) {
            Logger.error(this, "Cannot write node file!: "+e+" : "+"node-"+portNumber);
        }
    }
    
    private void writeNodeFile(File orig, File backup) throws IOException {
        SimpleFieldSet fs = exportPrivateFieldSet();
        orig.renameTo(backup);
        FileOutputStream fos = new FileOutputStream(orig);
        OutputStreamWriter osr = new OutputStreamWriter(fos);
        fs.writeTo(osr);
        osr.close();
    }

    private void initNodeFileSettings(RandomSource r) {
        Logger.normal(this, "Creating new node file from scratch");
        // Don't need to set portNumber
        // FIXME use a real IP!
    	myIdentity = new byte[32];
    	r.nextBytes(myIdentity);
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new Error(e);
        }
        identityHash = md.digest(myIdentity);
        identityHashHash = md.digest(identityHash);
        myName = newName();
        this.myCryptoGroup = Global.DSAgroupBigA;
        this.myPrivKey = new DSAPrivateKey(myCryptoGroup, r);
        this.myPubKey = new DSAPublicKey(myCryptoGroup, myPrivKey);
    }

    /**
     * Read the config file from the arguments.
     * Then create a node.
     * Anything that needs static init should ideally be in here.
     */
    public static void main(String[] args) throws IOException {
    	if(args.length>1) {
    		System.out.println("Usage: $ java freenet.node.Node <configFile>");
    		return;
    	}
    	
    	File configFilename;
    	if(args.length == 0) {
    		System.out.println("Using default config filename freenet.ini");
    		configFilename = new File("freenet.ini");
    	} else
    		configFilename = new File(args[0]);
    		
    	// set Java's DNS cache not to cache forever, since many people
    	// use dyndns hostnames
    	java.security.Security.setProperty("networkaddress.cache.ttl" , "300");
    	
    	FilePersistentConfig cfg = new FilePersistentConfig(configFilename);
    	
    	// First, set up logging. It is global, and may be shared between several nodes.
    	
    	SubConfig loggingConfig = new SubConfig("logger", cfg);
    	
    	try {
			logConfigHandler = new LoggingConfigHandler(loggingConfig);
		} catch (InvalidConfigValueException e) {
			System.err.println("Error: could not set up logging: "+e.getMessage());
			e.printStackTrace();
			return;
		}
    	
    	// Setup RNG

    	RandomSource random = new Yarrow();
    	
        DiffieHellman.init(random);
        
        Thread t = new Thread(new MemoryChecker(), "Memory checker");
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
        /*
        SimpleToadletServer server = new SimpleToadletServer(port+2000);
        FproxyToadlet fproxy = new FproxyToadlet(n.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS));
        PproxyToadlet pproxy = new PproxyToadlet(n.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS), n.pluginManager);
        server.register(fproxy, "/", false);
        server.register(pproxy, "/plugins/", true);
        
        System.out.println("Starting fproxy on port "+(port+2000));
        new FCPServer(port+3000, n);
        System.out.println("Starting FCP server on port "+(port+3000));
        //server.register(fproxy, "/SSK@", false);
        //server.register(fproxy, "/KSK@", false);
        */
    	Node node;
		try {
			node = new Node(cfg, random);
	    	node.start(false);
		} catch (NodeInitException e) {
			System.err.println("Failed to load node: "+e.getMessage());
			e.printStackTrace();
			System.exit(e.exitCode);
		}
    }
    
    static class NodeInitException extends Exception {
    	// One of the exit codes from above
    	public final int exitCode;
    	static final long serialVersionUID=0;
    	
    	NodeInitException(int exitCode, String msg) {
    		super(msg+" ("+exitCode+")");
    		this.exitCode = exitCode;
    	}
    }

    /**
     * Create a Node from a Config object.
     * @param config The Config object for this node.
     * @param random The random number generator for this node. Passed in because we may want
     * to use a non-secure RNG for e.g. one-JVM live-code simulations. Should be a Yarrow in
     * a production node.
     * @throws NodeInitException If the node initialization fails.
     */
    private Node(Config config, RandomSource random) throws NodeInitException {
    	
    	// Easy stuff
    	startupTime = System.currentTimeMillis();
    	throttleWindow = new ThrottleWindowManager(2.0);
        alerts = new UserAlertManager();
        recentlyCompletedIDs = new LRUQueue();
    	this.config = config;
    	this.random = random;
    	cachedPubKeys = new LRUHashtable();
		lm = new LocationManager(random);
    	try {
			localhostAddress = InetAddress.getByName("127.0.0.1");
		} catch (UnknownHostException e3) {
			// Does not do a reverse lookup, so this is impossible
			throw new Error(e3);
		}
        ipDetector = new IPAddressDetector(10*1000, this);
        requestSenders = new HashMap();
        transferringRequestSenders = new HashMap();
        insertSenders = new HashMap();
        runningUIDs = new HashSet();
        ps = new PacketSender(this);
        // FIXME maybe these should persist? They need to be private though, so after the node/peers split. (bug 51).
        decrementAtMax = random.nextDouble() <= DECREMENT_AT_MAX_PROB;
        decrementAtMin = random.nextDouble() <= DECREMENT_AT_MIN_PROB;
        bootID = random.nextLong();
        throttledPacketSendAverage =
        	new TimeDecayingRunningAverage(1, 60000 /* should be significantly longer than a typical transfer */, 0, Long.MAX_VALUE);

    	// Setup node-specific configuration
    	
    	SubConfig nodeConfig = new SubConfig("node", config);

    	// IP address override
    	
    	nodeConfig.register("ipAddressOverride", "", 0, true, "IP address override", "IP address override (not usually needed)", new StringCallback() {

			public String get() {
				if(overrideIPAddress == null) return "";
				else return Peer.getHostName(overrideIPAddress);
			}
			
			public void set(String val) throws InvalidConfigValueException {
				// FIXME do we need to tell anyone?
				if(val.length() == 0) {
					// Set to null
					overrideIPAddress = null;
					lastIPAddress = null;
					return;
				}
				InetAddress addr;
				try {
					addr = InetAddress.getByName(val);
				} catch (UnknownHostException e) {
					throw new InvalidConfigValueException("Unknown host: "+e.getMessage());
				}
				overrideIPAddress = addr;
				lastIPAddress = null;
			}
    		
    	});
    	
    	String ipOverrideString = nodeConfig.getString("ipAddressOverride");
    	if(ipOverrideString.length() == 0)
    		overrideIPAddress = null;
    	else {
			try {
				overrideIPAddress = InetAddress.getByName(ipOverrideString);
			} catch (UnknownHostException e) {
				String msg = "Unknown host: "+ipOverrideString+" in config: "+e.getMessage();
				Logger.error(this, msg);
				System.err.println(msg+" but starting up anyway with no IP override");
				overrideIPAddress = null;
			}
    	}
    	
    	// Determine the port number
    	
    	nodeConfig.register("listenPort", -1 /* means random */, 1, true, "FNP port number (UDP)", "UDP port for node-to-node communications (Freenet Node Protocol)",
    			new IntCallback() {
					public int get() {
						return portNumber;
					}
					public void set(int val) throws InvalidConfigValueException {
						// FIXME implement on the fly listenPort changing
						// Note that this sort of thing should be the exception rather than the rule!!!!
						String msg = "Switching listenPort on the fly not yet supported!";
						Logger.error(this, msg);
						throw new InvalidConfigValueException(msg);
					}
    	});
    	
    	int port=-1;
    	try{
    		port=nodeConfig.getInt("listenPort");
    	}catch (Exception e){
    		Logger.error(this, "Caught "+e, e);
    		System.err.println(e);
    		e.printStackTrace();
    		port=-1;
    	}
    	
    	UdpSocketManager u = null;
    	
    	if(port > 65535) {
    		throw new NodeInitException(EXIT_IMPOSSIBLE_USM_PORT, "Impossible port number: "+port);
    	} else if(port == -1) {
    		// Pick a random port
    		for(int i=0;i<200000;i++) {
    			int portNo = 1024 + random.nextInt(65535-1024);
    			try {
    				u = new UdpSocketManager(portNo);
    				port = u.getPortNumber();
    				break;
    			} catch (SocketException e) {
    				Logger.normal(this, "Could not use port: "+portNo+": "+e, e);
    				System.err.println("Could not use port: "+portNo+": "+e);
    				e.printStackTrace();
    				continue;
    			}
    		}
    		if(u == null)
    			throw new NodeInitException(EXIT_NO_AVAILABLE_UDP_PORTS, "Could not find an available UDP port number for FNP (none specified)");
    	} else {
    		try {
    			u = new UdpSocketManager(port);
    		} catch (SocketException e) {
    			throw new NodeInitException(EXIT_IMPOSSIBLE_USM_PORT, "Could not bind to port: "+port+" (node already running?)");
    		}
    	}
    	usm = u;
    	
        System.out.println("Port number: "+port);
        portNumber = port;
        
        Logger.normal(Node.class, "Creating node...");

        // Bandwidth limit

        // FIXME These should not be static !!!! Need a context object for BT for bwlimiting.
        // See bug 77
        nodeConfig.register("outputBandwidthLimit", "15K", 3, false, 
        		"Output bandwidth limit", "Hard output bandwidth limit (bytes/sec); the node should almost never exceed this", 
        		new IntCallback() {
					public int get() {
						return BlockTransmitter.getHardBandwidthLimit();
					}
					public void set(int val) throws InvalidConfigValueException {
						BlockTransmitter.setHardBandwidthLimit(val);
					}
        });
        
        int obwLimit = nodeConfig.getInt("outputBandwidthLimit");
        BlockTransmitter.setHardBandwidthLimit(obwLimit);
        // FIXME add an averaging/long-term/soft bandwidth limit. (bug 76)
        // There is already untested support for this in BlockTransmitter.
        // No long-term limit for now.
        BlockTransmitter.setSoftBandwidthLimit(0, 0);
        
        // SwapRequestInterval
        
        nodeConfig.register("swapRequestSendInterval", 2000, 4, true,
        		"Swap request send interval (ms)", "Interval between swap attempting to send swap requests in milliseconds. Leave this alone!",
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
        	if(!(x == Logger.MINOR || x == Logger.DEBUG)) {
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
        		"Note that while we no longer have explicit back-doors enabled, this version of Freenet is still a very early alpha, and may well have numerous bugs and design flaws.\n"+
        		"In particular: YOU ARE WIDE OPEN TO YOUR IMMEDIATE DARKNET PEERS! They can eavesdrop on your requests with relatively little difficulty at present (correlation attacks etc).";
        	Logger.normal(this, s);
        	System.err.println(s);
        	testnetEnabled = false;
        	FileLoggerHook flh = logConfigHandler.getFileLoggerHook();
        	if(flh != null) flh.deleteAllOldLogFiles();
        }
        
        if(wasTestnet != testnetEnabled) {
        	Logger.error(this, "Switched from testnet mode to non-testnet mode or vice versa! Regenerating pubkey, privkey, and deleting logs.");
            this.myCryptoGroup = Global.DSAgroupBigA;
            this.myPrivKey = new DSAPrivateKey(myCryptoGroup, random);
            this.myPubKey = new DSAPublicKey(myCryptoGroup, myPrivKey);
        	
        	
        }

        // Directory for node-related files other than store
        
        nodeConfig.register("nodeDir", ".", 6, true, "Node directory", "Name of directory to put node-related files e.g. peers list in", 
        		new StringCallback() {
					public String get() {
						return nodeDir.getPath();
					}
					public void set(String val) throws InvalidConfigValueException {
						if(nodeDir.equals(new File(val))) return;
						// FIXME
						throw new InvalidConfigValueException("Moving node directory on the fly not supported at present");
					}
        });
        
        nodeDir = new File(nodeConfig.getString("nodeDir"));
        if(!((nodeDir.exists() && nodeDir.isDirectory()) || (nodeDir.mkdir()))) {
        	String msg = "Could not find or create datastore directory";
        	throw new NodeInitException(EXIT_BAD_NODE_DIR, msg);
        }

        // After we have set up testnet and IP address, load the node file
        try {
        	// FIXME should take file directly?
        	readNodeFile(new File(nodeDir, "node-"+portNumber).getPath(), random);
        } catch (IOException e) {
            try {
                readNodeFile(new File("node-"+portNumber+".bak").getPath(), random);
            } catch (IOException e1) {
                initNodeFileSettings(random);
            }
        }

        // Then read the peers
        peers = new PeerManager(this, new File(nodeDir, "peers-"+portNumber).getPath());
        peers.writePeers();
        peers.checkEmpty();
        nodePinger = new NodePinger(this);

        usm.setDispatcher(dispatcher=new NodeDispatcher(this));
        usm.setLowLevelFilter(packetMangler = new FNPPacketMangler(this));

        // Temp files
        
        nodeConfig.register("tempDir", new File(nodeDir, "temp-"+portNumber).toString(), 6, true, "Temp files directory", "Name of directory to put temporary files in", 
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
        	throw new NodeInitException(EXIT_BAD_TEMP_DIR, msg);
        }
        
        try {
			tempFilenameGenerator = new FilenameGenerator(random, true, tempDir, "temp-");
		} catch (IOException e) {
        	String msg = "Could not find or create temporary directory (filename generator)";
        	throw new NodeInitException(EXIT_BAD_TEMP_DIR, msg);
		}
		tempBucketFactory = new PaddedEphemerallyEncryptedBucketFactory(new TempBucketFactory(tempFilenameGenerator), random, 1024);

        // Persistent temp files
		
		nodeConfig.register("persistentTempDir", new File(nodeDir, "persistent-temp-"+portNumber).toString(), 7, true, "Persistent temp files directory", "Name of directory to put persistent temp files in",
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
		} catch (IOException e2) {
        	String msg = "Could not find or create persistent temporary directory";
        	throw new NodeInitException(EXIT_BAD_TEMP_DIR, msg);
		}
		
        // Datastore
        
        nodeConfig.register("storeSize", "1G", 8, false, "Store size in bytes", "Store size in bytes", 
        		new LongCallback() {

					public long get() {
						return maxStoreKeys * sizePerKey;
					}

					public void set(long storeSize) throws InvalidConfigValueException {
						if(storeSize < 0 || storeSize < (32 * 1024 * 1024))
							throw new InvalidConfigValueException("Invalid store size");
						long newMaxStoreKeys = storeSize / sizePerKey;
						if(newMaxStoreKeys == maxStoreKeys) return;
						// Update each datastore
						maxStoreKeys = newMaxStoreKeys;
						chkDatastore.setMaxKeys(maxStoreKeys);
						sskDatastore.setMaxKeys(maxStoreKeys);
						pubKeyDatastore.setMaxKeys(maxStoreKeys);
					}
        });
        
        long storeSize = nodeConfig.getLong("storeSize");
        
        if(/*storeSize < 0 || */storeSize < (32 * 1024 * 1024)) { // totally arbitrary minimum!
        	throw new NodeInitException(EXIT_INVALID_STORE_SIZE, "Invalid store size");
        }

        maxStoreKeys = storeSize / sizePerKey;
        
        nodeConfig.register("storeDir", ".", 9, true, "Store directory", "Name of directory to put store files in", 
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
        	throw new NodeInitException(EXIT_STORE_OTHER, msg);
        }

        try {
            chkDatastore = new BerkeleyDBFreenetStore(storeDir.getPath()+File.separator+"store-"+portNumber, maxStoreKeys, 32768, CHKBlock.TOTAL_HEADERS_LENGTH);
            sskDatastore = new BerkeleyDBFreenetStore(storeDir.getPath()+File.separator+"sskstore-"+portNumber, maxStoreKeys, 1024, SSKBlock.TOTAL_HEADERS_LENGTH);
            pubKeyDatastore = new BerkeleyDBFreenetStore(storeDir.getPath()+File.separator+"pubkeystore-"+portNumber, maxStoreKeys, DSAPublicKey.PADDED_SIZE, 0);
        } catch (FileNotFoundException e1) {
        	String msg = "Could not open datastore: "+e1;
            Logger.error(this, msg, e1);
            System.err.println(msg);
            throw new NodeInitException(EXIT_STORE_FILE_NOT_FOUND, msg);
        } catch (IOException e1) {
        	String msg = "Could not open datastore: "+e1;
            Logger.error(this, msg, e1);
            System.err.println(msg);
            throw new NodeInitException(EXIT_STORE_IOEXCEPTION, msg);
        } catch (Exception e1) {
        	String msg = "Could not open datastore: "+e1;
            Logger.error(this, msg, e1);
            System.err.println(msg);
            throw new NodeInitException(EXIT_STORE_OTHER, msg);
        }
        
        // Downloads directory
        
        nodeConfig.register("downloadsDir", "downloads", 10, false, "Default download directory", "The directory to save downloaded files into by default", new StringCallback() {

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
        	throw new NodeInitException(EXIT_BAD_DOWNLOADS_DIR, "Could not find or create default downloads directory");
        }

        // Name
        nodeConfig.register("name", myName, 11, false, "Node name for darknet", "Node name; you may want to set this to something descriptive if running on darknet e.g. Fred Blogg's Node; it is visible to any connecting node",
        		new NodeNameCallback(this));
        nodeNameUserAlert = new MeaningfulNodeNameUserAlert();
        myName = nodeConfig.getString("name");
        
        nodeConfig.finishedInitialization();
        writeNodeFile();
        
        // FIXME make all the below arbitrary constants configurable!
        
		archiveManager = new ArchiveManager(MAX_ARCHIVE_HANDLERS, MAX_CACHED_ARCHIVE_DATA, MAX_ARCHIVE_SIZE, MAX_ARCHIVED_FILE_SIZE, MAX_CACHED_ELEMENTS, random, tempFilenameGenerator);
		chkRequestThrottle = new MyRequestThrottle(throttleWindow, 5000, "CHK Request");
		chkRequestStarter = new RequestStarter(this, chkRequestThrottle, "CHK Request starter ("+portNumber+")");
		chkFetchScheduler = new ClientRequestScheduler(false, false, random, chkRequestStarter, this);
		chkRequestStarter.setScheduler(chkFetchScheduler);
		chkRequestStarter.start();
		//insertThrottle = new ChainedRequestThrottle(10000, 2.0F, requestThrottle);
		// FIXME reenable the above
		chkInsertThrottle = new MyRequestThrottle(throttleWindow, 10000, "CHK Insert");
		chkInsertStarter = new RequestStarter(this, chkInsertThrottle, "CHK Insert starter ("+portNumber+")");
		chkPutScheduler = new ClientRequestScheduler(true, false, random, chkInsertStarter, this);
		chkInsertStarter.setScheduler(chkPutScheduler);
		chkInsertStarter.start();

		sskRequestThrottle = new MyRequestThrottle(throttleWindow, 5000, "SSK Request");
		sskRequestStarter = new RequestStarter(this, sskRequestThrottle, "SSK Request starter ("+portNumber+")");
		sskFetchScheduler = new ClientRequestScheduler(false, true, random, sskRequestStarter, this);
		sskRequestStarter.setScheduler(sskFetchScheduler);
		sskRequestStarter.start();
		//insertThrottle = new ChainedRequestThrottle(10000, 2.0F, requestThrottle);
		// FIXME reenable the above
		sskInsertThrottle = new MyRequestThrottle(throttleWindow, 10000, "SSK Insert");
		sskInsertStarter = new RequestStarter(this, sskInsertThrottle, "SSK Insert starter ("+portNumber+")");
		sskPutScheduler = new ClientRequestScheduler(true, true, random, sskInsertStarter, this);
		sskInsertStarter.setScheduler(sskPutScheduler);
		sskInsertStarter.start();

		uskManager = new USKManager(this);
		
		// And finally, Initialize the plugin manager
		pluginManager = new PluginManager(this);
    }
    
	private InetAddress resolve(String val) {
		try {
			if(val == null || val.length() == 0)
				return null;
			else
				return InetAddress.getByName(val);
		} catch (UnknownHostException e) {
			Logger.error(this, "Ignoring unresolvable overridden IP address: "+overrideIPAddress);
			return null;
		}
	}
	
    void start(boolean noSwaps) throws NodeInitException {
        if(!noSwaps)
            lm.startSender(this, this.swapInterval);
        ps.start();
        usm.start();
        
        // Start services
        
        // TMCI
        try{
        	TextModeClientInterfaceServer.maybeCreate(this, config);
        } catch (IOException e) {
			e.printStackTrace();
			throw new NodeInitException(EXIT_COULD_NOT_START_TMCI, "Could not start TMCI: "+e);
		}
        
        // FCP (including persistent requests so needs to start before Fproxy)
        try {
			fcpServer = FCPServer.maybeCreate(this, config);
		} catch (IOException e) {
			throw new NodeInitException(EXIT_COULD_NOT_START_FCP, "Could not start FCP: "+e);
		} catch (InvalidConfigValueException e) {
			throw new NodeInitException(EXIT_COULD_NOT_START_FCP, "Could not start FCP: "+e);
		}
        
        // Fproxy
        // FIXME this is a hack, the real way to do this is plugins
        try {
			FproxyToadlet.maybeCreateFproxyEtc(this, config);
		} catch (IOException e) {
			e.printStackTrace();
			throw new NodeInitException(EXIT_COULD_NOT_START_FPROXY, "Could not start fproxy: "+e);
		} catch (InvalidConfigValueException e) {
			throw new NodeInitException(EXIT_COULD_NOT_START_FPROXY, "Could not start fproxy: "+e);			
		}
		
		/*
        SimpleToadletServer server = new SimpleToadletServer(port+2000);
        FproxyToadlet fproxy = new FproxyToadlet(n.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS));
        PproxyToadlet pproxy = new PproxyToadlet(n.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS), n.pluginManager);
        server.register(fproxy, "/", false);
        server.register(pproxy, "/plugins/", true);
		 * */
        
        // After everything has been created, write the config file back to disk.
        config.finishedInit();
        config.store();
        
        // Start testnet handler
		if(testnetHandler != null)
			testnetHandler.start();
		
        persistentTempBucketFactory.completedInit();
        
		Thread t = new Thread(ipDetector, "IP address re-detector");
		t.setDaemon(true);
		t.start();
    }
    
    public ClientKeyBlock realGetKey(ClientKey key, boolean localOnly, boolean cache, boolean ignoreStore) throws LowLevelGetException {
    	if(key instanceof ClientCHK)
    		return realGetCHK((ClientCHK)key, localOnly, cache, ignoreStore);
    	else if(key instanceof ClientSSK)
    		return realGetSSK((ClientSSK)key, localOnly, cache, ignoreStore);
    	else
    		throw new IllegalArgumentException("Not a CHK or SSK: "+key);
    }
    
    /**
     * Really trivially simple client interface.
     * Either it succeeds or it doesn't.
     */
    ClientCHKBlock realGetCHK(ClientCHK key, boolean localOnly, boolean cache, boolean ignoreStore) throws LowLevelGetException {
    	long startTime = System.currentTimeMillis();
    	long uid = random.nextLong();
        if(!lockUID(uid)) {
            Logger.error(this, "Could not lock UID just randomly generated: "+uid+" - probably indicates broken PRNG");
            throw new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR);
        }
        Object o = makeRequestSender(key.getNodeCHK(), MAX_HTL, uid, null, lm.loc.getValue(), localOnly, cache, ignoreStore);
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
        while(true) {
        	if(rs.waitUntilStatusChange() && (!rejectedOverload)) {
        		// See below; inserts count both
        		throttleWindow.rejectedOverload();
        		rejectedOverload = true;
        	}

        	int status = rs.getStatus();
        	
        	if(status == RequestSender.NOT_FINISHED) 
        		continue;
        	
        	if(status == RequestSender.TIMED_OUT ||
        			status == RequestSender.GENERATED_REJECTED_OVERLOAD) {
        		if(!rejectedOverload) {
        			// See below
        			throttleWindow.rejectedOverload();
        			rejectedOverload = true;
        		}
        	} else {
        		if(status == RequestSender.DATA_NOT_FOUND ||
        				status == RequestSender.SUCCESS ||
        				status == RequestSender.ROUTE_NOT_FOUND ||
        				status == RequestSender.VERIFY_FAILURE) {
        			long rtt = System.currentTimeMillis() - startTime;
        			throttleWindow.requestCompleted();
        			chkRequestThrottle.successfulCompletion(rtt);
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

    /**
     * Really trivially simple client interface.
     * Either it succeeds or it doesn't.
     */
    ClientSSKBlock realGetSSK(ClientSSK key, boolean localOnly, boolean cache, boolean ignoreStore) throws LowLevelGetException {
    	long startTime = System.currentTimeMillis();
    	long uid = random.nextLong();
        if(!lockUID(uid)) {
            Logger.error(this, "Could not lock UID just randomly generated: "+uid+" - probably indicates broken PRNG");
            throw new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR);
        }
        Object o = makeRequestSender(key.getNodeKey(), MAX_HTL, uid, null, lm.loc.getValue(), localOnly, cache, ignoreStore);
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
        while(true) {
        	if(rs.waitUntilStatusChange() && (!rejectedOverload)) {
        		throttleWindow.rejectedOverload();
        		rejectedOverload = true;
        	}

        	int status = rs.getStatus();
        	
        	if(status == RequestSender.NOT_FINISHED) 
        		continue;
        	
        	if(status == RequestSender.TIMED_OUT ||
        			status == RequestSender.GENERATED_REJECTED_OVERLOAD) {
        		if(!rejectedOverload) {
        			throttleWindow.rejectedOverload();
        			rejectedOverload = true;
        		}
        	} else {
        		if(status == RequestSender.DATA_NOT_FOUND ||
        				status == RequestSender.SUCCESS ||
        				status == RequestSender.ROUTE_NOT_FOUND ||
        				status == RequestSender.VERIFY_FAILURE) {
        			long rtt = System.currentTimeMillis() - startTime;
        			throttleWindow.requestCompleted();
        			sskRequestThrottle.successfulCompletion(rtt);
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

    public void realPut(ClientKeyBlock block, boolean cache) throws LowLevelPutException {
    	if(block instanceof ClientCHKBlock)
    		realPutCHK((ClientCHKBlock)block, cache);
    	else if(block instanceof ClientSSKBlock)
    		realPutSSK((ClientSSKBlock)block, cache);
    	else
    		throw new IllegalArgumentException("Unknown put type "+block.getClass());
    }
    
    public void realPutCHK(ClientCHKBlock block, boolean cache) throws LowLevelPutException {
        byte[] data = block.getData();
        byte[] headers = block.getHeaders();
        PartiallyReceivedBlock prb = new PartiallyReceivedBlock(PACKETS_IN_BLOCK, PACKET_SIZE, data);
        CHKInsertSender is;
        long uid = random.nextLong();
        if(!lockUID(uid)) {
            Logger.error(this, "Could not lock UID just randomly generated: "+uid+" - probably indicates broken PRNG");
            throw new LowLevelPutException(LowLevelPutException.INTERNAL_ERROR);
        }
        long startTime = System.currentTimeMillis();
        synchronized(this) {
        	if(cache) {
        		try {
        			chkDatastore.put(block);
        		} catch (IOException e) {
        			Logger.error(this, "Datastore failure: "+e, e);
        		}
        	}
            is = makeInsertSender((NodeCHK)block.getClientKey().getNodeKey(), 
                    MAX_HTL, uid, null, headers, prb, false, lm.getLocation().getValue(), cache);
        }
        boolean hasForwardedRejectedOverload = false;
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
    		if((!hasForwardedRejectedOverload) && is.receivedRejectedOverload()) {
    			hasForwardedRejectedOverload = true;
    			throttleWindow.rejectedOverload();
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
    		if(is.anyTransfersFailed() && (!hasForwardedRejectedOverload)) {
    			hasForwardedRejectedOverload = true; // not strictly true but same effect
    			throttleWindow.rejectedOverload();
    		}        		
        }
        
        Logger.minor(this, "Completed "+uid+" overload="+hasForwardedRejectedOverload+" "+is.getStatusString());
        
        // Finished?
        if(!hasForwardedRejectedOverload) {
        	// Is it ours? Did we send a request?
        	if(is.sentRequest() && is.uid == uid && (is.getStatus() == CHKInsertSender.ROUTE_NOT_FOUND 
        			|| is.getStatus() == CHKInsertSender.SUCCESS)) {
        		// It worked!
        		long endTime = System.currentTimeMillis();
        		long len = endTime - startTime;
        		
        		chkInsertThrottle.successfulCompletion(len);
        		throttleWindow.requestCompleted();
        	}
        }
        
        if(is.getStatus() == CHKInsertSender.SUCCESS) {
        	Logger.normal(this, "Succeeded inserting "+block);
        	return;
        } else {
        	int status = is.getStatus();
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

    public void realPutSSK(ClientSSKBlock block, boolean cache) throws LowLevelPutException {
        SSKInsertSender is;
        long uid = random.nextLong();
        if(!lockUID(uid)) {
            Logger.error(this, "Could not lock UID just randomly generated: "+uid+" - probably indicates broken PRNG");
            throw new LowLevelPutException(LowLevelPutException.INTERNAL_ERROR);
        }
        long startTime = System.currentTimeMillis();
        synchronized(this) {
        	if(cache) {
        		try {
        			sskDatastore.put(block);
        		} catch (IOException e) {
        			Logger.error(this, "Datastore failure: "+e, e);
        		}
        	}
            is = makeInsertSender(block, 
                    MAX_HTL, uid, null, false, lm.getLocation().getValue(), cache);
        }
        boolean hasForwardedRejectedOverload = false;
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
    		if((!hasForwardedRejectedOverload) && is.receivedRejectedOverload()) {
    			hasForwardedRejectedOverload = true;
    			throttleWindow.rejectedOverload();
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
        
        Logger.minor(this, "Completed "+uid+" overload="+hasForwardedRejectedOverload+" "+is.getStatusString());
        
        // Finished?
        if(!hasForwardedRejectedOverload) {
        	// Is it ours? Did we send a request?
        	if(is.sentRequest() && is.uid == uid && (is.getStatus() == SSKInsertSender.ROUTE_NOT_FOUND 
        			|| is.getStatus() == SSKInsertSender.SUCCESS)) {
        		// It worked!
        		long endTime = System.currentTimeMillis();
        		long rtt = endTime - startTime;
        		throttleWindow.requestCompleted();
        		sskInsertThrottle.successfulCompletion(rtt);
        	}
        }

        if(is.hasCollided()) {
        	// Store it locally so it can be fetched immediately, and overwrites any locally inserted.
        	store(is.getBlock());
        	throw new LowLevelPutException(LowLevelPutException.COLLISION);
        }
        
        if(is.getStatus() == SSKInsertSender.SUCCESS) {
        	Logger.normal(this, "Succeeded inserting "+block);
        	return;
        } else {
        	int status = is.getStatus();
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

    long lastAcceptedRequest = -1;
    
    public synchronized boolean shouldRejectRequest() {
    	long now = System.currentTimeMillis();
    	
    	// Round trip time
    	double pingTime = nodePinger.averagePingTime();
    	if(pingTime > MAX_PING_TIME) {
    		if(now - lastAcceptedRequest > MAX_INTERREQUEST_TIME) {
    			lastAcceptedRequest = now;
    			return false;
    		}
    		return true;
    	}
    	if(pingTime > SUB_MAX_PING_TIME) {
    		double x = (pingTime - SUB_MAX_PING_TIME) / (MAX_PING_TIME - SUB_MAX_PING_TIME);
    		if(random.nextDouble() < x)
    			return true;
    	}
    	
    	// Bandwidth limited packets
    	
    	double bwlimitDelayTime = this.throttledPacketSendAverage.currentValue();
    	if(pingTime > MAX_THROTTLE_DELAY) {
    		if(now - lastAcceptedRequest > MAX_INTERREQUEST_TIME) {
    			lastAcceptedRequest = now;
    			return false;
    		}
    		return true;
    	}
    	if(bwlimitDelayTime > SUB_MAX_THROTTLE_DELAY) {
    		double x = (pingTime - SUB_MAX_THROTTLE_DELAY) / (MAX_THROTTLE_DELAY - SUB_MAX_THROTTLE_DELAY);
    		if(random.nextDouble() < x)
    			return true;
    	}
    	
    	lastAcceptedRequest = now;
    	return false;
    }
    
    public SimpleFieldSet exportPrivateFieldSet() {
    	SimpleFieldSet fs = exportPublicFieldSet();
    	fs.put("dsaPrivKey", myPrivKey.asFieldSet());
    	return fs;
    }
    
    /**
     * Export my reference so that another node can connect to me.
     * @return
     */
    public SimpleFieldSet exportPublicFieldSet() {
        SimpleFieldSet fs = new SimpleFieldSet(true);
        InetAddress ip = getPrimaryIPAddress();
        fs.put("base64", "true");
        if(ip != null)
        	fs.put("physical.udp", Peer.getHostName(ip)+":"+portNumber);
        fs.put("identity", Base64.encode(myIdentity));
        fs.put("location", Double.toString(lm.getLocation().getValue()));
        fs.put("version", Version.getVersionString());
        fs.put("testnet", Boolean.toString(testnetEnabled));
        fs.put("lastGoodVersion", Version.getLastGoodVersionString());
        if(testnetEnabled)
        	fs.put("testnetPort", Integer.toString(testnetHandler.testnetPort));
        fs.put("myName", myName);
        fs.put("dsaGroup", myCryptoGroup.asFieldSet());
        fs.put("dsaPubKey", myPubKey.asFieldSet());
        Logger.minor(this, "My reference: "+fs);
        return fs;
    }

    InetAddress overrideIPAddress;
    /** IP address from last time */
    InetAddress oldIPAddress;
    /** Last detected IP address */
    InetAddress lastIPAddress;
    
    /**
     * @return Our current main IP address.
     * FIXME - we should support more than 1, and we should do the
     * detection properly with NetworkInterface, and we should use
     * third parties if available and UP&P if available.
     */
    InetAddress detectPrimaryIPAddress() {
        if(overrideIPAddress != null) {
            Logger.minor(this, "Returning overridden address: "+overrideIPAddress);
            return overrideIPAddress;
        }
        Logger.minor(this, "IP address not overridden");
       	InetAddress addr = ipDetector.getAddress();
       	if(addr != null) {
       		lastIPAddress = addr;
       		return addr;
       	}
   		// Try to pick it up from our connections
       	if(peers != null) {
       		PeerNode[] peerList = peers.connectedPeers;
       		HashMap countsByPeer = new HashMap();
       		// FIXME use a standard mutable int object, we have one somewhere
       		for(int i=0;i<peerList.length;i++) {
       			Peer p = peerList[i].getRemoteDetectedPeer();
       			if(p == null || p.isNull()) continue;
       			InetAddress ip = p.getAddress();
       			if(!IPUtil.checkAddress(p.getAddress())) continue;
       			if(countsByPeer.containsKey(ip)) {
       				Integer count = (Integer) countsByPeer.get(ip);
       				Integer newCount = new Integer(count.intValue()+1);
       				countsByPeer.put(ip, newCount);
       			} else {
       				countsByPeer.put(ip, new Integer(1));
       			}
       		}
       		if(countsByPeer.size() == 0) return null;
       		Iterator it = countsByPeer.keySet().iterator();
       		if(countsByPeer.size() == 1) {
       			return (InetAddress) it.next();
       		}
       		// Pick most popular address
       		// FIXME use multi-homing here
       		InetAddress best = null;
       		int bestPopularity = 0;
       		while(it.hasNext()) {
       			InetAddress cur = (InetAddress) it.next();
       			int curPop = ((Integer) (countsByPeer.get(best))).intValue();
       			if(curPop > bestPopularity) {
       				bestPopularity = curPop;
       				best = cur;
       			}
       		}
       		lastIPAddress = best;
       	}
       	else {
       		lastIPAddress = oldIPAddress;
       	}
       	if (lastIPAddress == null) {
       		this.alerts.register(primaryIPUndetectedAlert);
       	}
       	else	 {
       		this.alerts.unregister(primaryIPUndetectedAlert);
       	}
       	return lastIPAddress;
    }

    InetAddress getPrimaryIPAddress() {
    	if(lastIPAddress == null) return detectPrimaryIPAddress();
    	return lastIPAddress;
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
        Message m = DMT.createFNPRoutedPing(uid, loc2, MAX_HTL, initialX);
        Logger.normal(this, "Message: "+m);
        
        dispatcher.handleRouted(m);
        // FIXME: might be rejected
        MessageFilter mf1 = MessageFilter.create().setField(DMT.UID, uid).setType(DMT.FNPRoutedPong).setTimeout(5000);
        try {
            //MessageFilter mf2 = MessageFilter.create().setField(DMT.UID, uid).setType(DMT.FNPRoutedRejected).setTimeout(5000);
            // Ignore Rejected - let it be retried on other peers
            m = usm.waitFor(mf1/*.or(mf2)*/);
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
    public synchronized Object makeRequestSender(Key key, short htl, long uid, PeerNode source, double closestLocation, boolean localOnly, boolean cache, boolean ignoreStore) {
        Logger.minor(this, "makeRequestSender("+key+","+htl+","+uid+","+source+") on "+portNumber);
        // In store?
        KeyBlock chk = null;
        if(!ignoreStore) {
        try {
        	if(key instanceof NodeCHK)
        		chk = chkDatastore.fetch((NodeCHK)key, !cache);
        	else if(key instanceof NodeSSK) {
        		NodeSSK k = (NodeSSK)key;
        		DSAPublicKey pubKey = k.getPubKey();
        		if(pubKey == null) {
        			pubKey = getKey(k.getPubKeyHash());
        			Logger.minor(this, "Fetched pubkey: "+pubKey+" "+(pubKey == null ? "" : pubKey.writeAsField()));
        			try {
						k.setPubKey(pubKey);
					} catch (SSKVerifyException e) {
						Logger.error(this, "Error setting pubkey: "+e, e);
					}
        		}
        		if(pubKey != null) {
        			Logger.minor(this, "Got pubkey: "+pubKey+" "+pubKey.writeAsField());
        			chk = sskDatastore.fetch((NodeSSK)key, !cache);
        		} else {
        			Logger.minor(this, "Not found because no pubkey: "+uid);
        		}
        	} else
        		throw new IllegalStateException("Unknown key type: "+key.getClass());
        } catch (IOException e) {
            Logger.error(this, "Error accessing store: "+e, e);
        }
        if(chk != null) return chk;
        }
        if(localOnly) return null;
        Logger.minor(this, "Not in store locally");
        
        // Transfer coalescing - match key only as HTL irrelevant
        RequestSender sender = (RequestSender) transferringRequestSenders.get(key);
        if(sender != null) {
            Logger.minor(this, "Data already being transferred: "+sender);
            return sender;
        }

        // HTL == 0 => Don't search further
        if(htl == 0) {
            Logger.minor(this, "No HTL");
            return null;
        }
        
        // Request coalescing
        KeyHTLPair kh = new KeyHTLPair(key, htl);
        sender = (RequestSender) requestSenders.get(kh);
        if(sender != null) {
            Logger.minor(this, "Found sender: "+sender+" for "+uid);
            return sender;
        }
        
        sender = new RequestSender(key, null, htl, uid, this, closestLocation, source);
        requestSenders.put(kh, sender);
        Logger.minor(this, "Created new sender: "+sender);
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
                return (p.key.equals(key) && p.htl == htl);
            } else return false;
        }
        
        public int hashCode() {
            return key.hashCode() ^ htl;
        }
        
        public String toString() {
            return key.toString()+":"+htl;
        }
    }

    /**
     * Add a RequestSender to our HashSet.
     */
    public synchronized void addSender(Key key, short htl, RequestSender sender) {
        KeyHTLPair kh = new KeyHTLPair(key, htl);
        requestSenders.put(kh, sender);
    }

    /**
     * Add a transferring RequestSender.
     */
    public synchronized void addTransferringSender(NodeCHK key, RequestSender sender) {
        transferringRequestSenders.put(key, sender);
    }

    public synchronized SSKBlock fetch(NodeSSK key) {
    	try {
    		return sskDatastore.fetch(key, false);
    	} catch (IOException e) {
    		Logger.error(this, "Cannot fetch data: "+e, e);
    		return null;
    	}
    }

    public synchronized CHKBlock fetch(NodeCHK key) {
    	try {
    		return chkDatastore.fetch(key, false);
    	} catch (IOException e) {
    		Logger.error(this, "Cannot fetch data: "+e, e);
    		return null;
    	}
    }
    
    /**
     * Store a datum.
     */
    public synchronized void store(CHKBlock block) {
        try {
            chkDatastore.put(block);
        } catch (IOException e) {
            Logger.error(this, "Cannot store data: "+e, e);
        }
    }

    public synchronized void store(SSKBlock block) {
    	try {
    		sskDatastore.put(block);
    		cacheKey(((NodeSSK)block.getKey()).getPubKeyHash(), ((NodeSSK)block.getKey()).getPubKey());
    	} catch (IOException e) {
    		Logger.error(this, "Cannot store data: "+e, e);
    	}
    }
    
    /**
     * Remove a sender from the set of currently transferring senders.
     */
    public synchronized void removeTransferringSender(NodeCHK key, RequestSender sender) {
        RequestSender rs = (RequestSender) transferringRequestSenders.remove(key);
        if(rs != sender) {
            Logger.error(this, "Removed "+rs+" should be "+sender+" for "+key+" in removeTransferringSender");
        }
    }

    /**
     * Remove a RequestSender from the map.
     */
    public synchronized void removeSender(Key key, short htl, RequestSender sender) {
        KeyHTLPair kh = new KeyHTLPair(key, htl);
        RequestSender rs = (RequestSender) requestSenders.remove(kh);
        if(rs != sender) {
            Logger.error(this, "Removed "+rs+" should be "+sender+" for "+key+","+htl+" in removeSender");
        }
    }

    /**
     * Remove an CHKInsertSender from the map.
     */
    public void removeInsertSender(Key key, short htl, AnyInsertSender sender) {
        KeyHTLPair kh = new KeyHTLPair(key, htl);
        AnyInsertSender is = (AnyInsertSender) insertSenders.remove(kh);
        if(is != sender) {
            Logger.error(this, "Removed "+is+" should be "+sender+" for "+key+","+htl+" in removeInsertSender");
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
        if(htl >= MAX_HTL) htl = MAX_HTL;
        if(htl <= 0) htl = 1;
        if(htl == MAX_HTL) {
            if(decrementAtMax) htl--;
            return htl;
        }
        if(htl == 1) {
            if(decrementAtMin) htl--;
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
    public synchronized CHKInsertSender makeInsertSender(NodeCHK key, short htl, long uid, PeerNode source,
            byte[] headers, PartiallyReceivedBlock prb, boolean fromStore, double closestLoc, boolean cache) {
        Logger.minor(this, "makeInsertSender("+key+","+htl+","+uid+","+source+",...,"+fromStore);
        KeyHTLPair kh = new KeyHTLPair(key, htl);
        CHKInsertSender is = (CHKInsertSender) insertSenders.get(kh);
        if(is != null) {
            Logger.minor(this, "Found "+is+" for "+kh);
            return is;
        }
        if(fromStore && !cache)
        	throw new IllegalArgumentException("From store = true but cache = false !!!");
        is = new CHKInsertSender(key, uid, headers, htl, source, this, prb, fromStore, closestLoc);
        Logger.minor(this, is.toString()+" for "+kh.toString());
        insertSenders.put(kh, is);
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
    public synchronized SSKInsertSender makeInsertSender(SSKBlock block, short htl, long uid, PeerNode source,
            boolean fromStore, double closestLoc, boolean cache) {
    	NodeSSK key = (NodeSSK) block.getKey();
    	if(key.getPubKey() == null) {
    		throw new IllegalArgumentException("No pub key when inserting");
    	}
    	cacheKey(key.getPubKeyHash(), key.getPubKey());
        Logger.minor(this, "makeInsertSender("+key+","+htl+","+uid+","+source+",...,"+fromStore);
        KeyHTLPair kh = new KeyHTLPair(key, htl);
        SSKInsertSender is = (SSKInsertSender) insertSenders.get(kh);
        if(is != null) {
            Logger.minor(this, "Found "+is+" for "+kh);
            return is;
        }
        if(fromStore && !cache)
        	throw new IllegalArgumentException("From store = true but cache = false !!!");
        is = new SSKInsertSender(block, uid, htl, source, this, fromStore, closestLoc);
        Logger.minor(this, is.toString()+" for "+kh.toString());
        insertSenders.put(kh, is);
        return is;
    }
    
    public boolean lockUID(long uid) {
    	Logger.minor(this, "Locking "+uid);
        Long l = new Long(uid);
        synchronized(runningUIDs) {
            if(runningUIDs.contains(l)) return false;
            runningUIDs.add(l);
            return true;
        }
    }
    
    public void unlockUID(long uid) {
    	Logger.minor(this, "Unlocking "+uid);
        Long l = new Long(uid);
        completed(uid);
        synchronized(runningUIDs) {
            if(!runningUIDs.remove(l))
                throw new IllegalStateException("Could not unlock "+uid+"!");
        }
    }

    /**
     * @return Some status information.
     */
    public String getStatus() {
    	StringBuffer sb = new StringBuffer();
    	if (peers != null)
    		sb.append(peers.getStatus());
    	sb.append("\nInserts: ");
    	int x = insertSenders.size();
    	sb.append(x);
    	if(x < 5 && x > 0) {
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
    	sb.append(requestSenders.size());
    	sb.append("\nTransferring requests: ");
    	sb.append(this.transferringRequestSenders.size());
    	sb.append('\n');
    	return sb.toString();
    }
    
    public int getNumInserts() {
        return insertSenders.size();
    }
    
    public int getNumRequests() {
	return requestSenders.size();
    }

    public int getNumTransferringRequests() {
	return transferringRequestSenders.size();
    }
    
    /**
     * @return Data String for freeviz.
     */
    public String getFreevizOutput() {
    	StringBuffer sb = new StringBuffer();
    	sb.append("\nrequests=");
    	sb.append(requestSenders.size());
    	
    	sb.append("\ntransferring_requests=");
    	sb.append(this.transferringRequestSenders.size());
    	
    	sb.append("\ninserts=");
    	sb.append(this.insertSenders.size());
    	sb.append("\n");
    	
    	
    	if (peers != null)
    		sb.append(peers.getFreevizOutput());
    	  		    	    	
    	return sb.toString();
    }

    /**
     * @return Our reference, compressed
     */
    public byte[] myPublicRefCompressed() {
        SimpleFieldSet fs = exportPublicFieldSet();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DeflaterOutputStream gis;
		gis = new DeflaterOutputStream(baos);
        OutputStreamWriter osw = new OutputStreamWriter(gis);
        try {
            fs.writeTo(osw);
        } catch (IOException e) {
            throw new Error(e);
        }
        try {
            osw.flush();
            gis.close();
        } catch (IOException e1) {
            throw new Error(e1);
        }
        byte[] buf = baos.toByteArray();
        byte[] obuf = new byte[buf.length + 1];
        obuf[0] = 1;
        System.arraycopy(buf, 0, obuf, 1, buf.length);
        return obuf;
        // FIXME support compression when noderefs get big enough for it to be useful
    }

    final LRUQueue recentlyCompletedIDs;

    static final int MAX_RECENTLY_COMPLETED_IDS = 10*1000;

    /**
     * Has a request completed with this ID recently?
     */
    public synchronized boolean recentlyCompleted(long id) {
        return recentlyCompletedIDs.contains(new Long(id));
    }
    
    /**
     * A request completed (regardless of success).
     */
    public synchronized void completed(long id) {
        recentlyCompletedIDs.push(new Long(id));
        while(recentlyCompletedIDs.size() > MAX_RECENTLY_COMPLETED_IDS)
            recentlyCompletedIDs.pop();
    }

	public HighLevelSimpleClient makeClient(short prioClass) {
		return new HighLevelSimpleClientImpl(this, archiveManager, tempBucketFactory, random, !DONT_CACHE_LOCAL_REQUESTS, prioClass);
	}
	
	private static class MemoryChecker implements Runnable {

		public void run() {
			Runtime r = Runtime.getRuntime();
			while(true) {
				for(int i=0;i<120;i++) {
					try {
						Thread.sleep(250);
					} catch (InterruptedException e) {
						// Ignore
					}
					Logger.minor(this, "Memory in use: "+(r.totalMemory()-r.freeMemory()));
				}
				try {
					Thread.sleep(250);
				} catch (InterruptedException e) {
					// Ignore
				}
				// FIXME
				// Do not remove until all known memory issues fixed,
				// Especially #66
				// This probably reduces performance, but it makes
				// memory usage *more predictable*. This will make
				// tracking down the sort of nasty unpredictable OOMs
				// we are getting much easier. 
				Logger.minor(this, "Memory in use before GC: "+(r.totalMemory()-r.freeMemory()));
				System.gc();
				System.runFinalization();
				Logger.minor(this, "Memory in use after GC: "+(r.totalMemory()-r.freeMemory()));
			}
		}
	}

	public BaseRequestThrottle getCHKRequestThrottle() {
		return chkRequestThrottle;
	}

	public BaseRequestThrottle getCHKInsertThrottle() {
		return chkInsertThrottle;
	}
	
	public BaseRequestThrottle getSSKRequestThrottle() {
		return sskRequestThrottle;
	}
	
	public BaseRequestThrottle getSSKInsertThrottle() {
		return sskInsertThrottle;
	}

	InetAddress lastIP;

	public void redetectAddress() {
		InetAddress newIP = detectPrimaryIPAddress();
		if(newIP.equals(lastIP)) return;
		writeNodeFile();
	}
	
	/**
	 * Look up a cached public key by its hash.
	 */
	public DSAPublicKey getKey(byte[] hash) {
		ImmutableByteArrayWrapper w = new ImmutableByteArrayWrapper(hash);
		Logger.minor(this, "Getting pubkey: "+HexUtil.bytesToHex(hash));
		synchronized(cachedPubKeys) {
			DSAPublicKey key = (DSAPublicKey) cachedPubKeys.get(w);
			if(key != null) {
				cachedPubKeys.push(w, key);
				Logger.minor(this, "Got "+HexUtil.bytesToHex(hash)+" from cache");
				return key;
			}
		}
		try {
			DSAPublicKey key = pubKeyDatastore.fetchPubKey(hash, false);
			if(key != null) {
				cacheKey(hash, key);
				Logger.minor(this, "Got "+HexUtil.bytesToHex(hash)+" from store");
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
	public void cacheKey(byte[] hash, DSAPublicKey key) {
		ImmutableByteArrayWrapper w = new ImmutableByteArrayWrapper(hash);
		synchronized(cachedPubKeys) {
			DSAPublicKey key2 = (DSAPublicKey) cachedPubKeys.get(w);
			if(key2 != null && !key2.equals(key)) {
				MessageDigest md256;
				// Check the hash.
				try {
					md256 = MessageDigest.getInstance("SHA-256");
				} catch (NoSuchAlgorithmException e) {
					throw new Error(e);
				}
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
						cacheKey(hash, key);
					}
				} else {
					Logger.error(this, "New hash is wrong");
				}
				throw new IllegalArgumentException("Wrong hash?? Already have different key with same hash!");
			}
			cachedPubKeys.push(w, key);
			while(cachedPubKeys.size() > MAX_CACHED_KEYS)
				cachedPubKeys.popKey();
		}
		try {
			pubKeyDatastore.put(hash, key);
		} catch (IOException e) {
			// FIXME deal with disk full, access perms etc; tell user about it.
			Logger.error(this, "Error accessing pubkey store: "+e, e);
		}
	}

	public boolean isTestnetEnabled() {
		return testnetEnabled;
	}

	public ClientKeyBlock fetchKey(ClientKey key) throws KeyVerifyException {
		if(key instanceof ClientCHK)
			return fetch((ClientCHK)key);
		else if(key instanceof ClientSSK)
			return fetch((ClientSSK)key);
		else
			throw new IllegalStateException("Don't know what to do with "+key);
	}

	private ClientKeyBlock fetch(ClientSSK clientSSK) throws SSKVerifyException {
		DSAPublicKey key = clientSSK.getPubKey();
		if(key == null) {
			key = getKey(clientSSK.pubKeyHash);
		}
		if(key == null) return null;
		clientSSK.setPublicKey(key);
		SSKBlock block = fetch((NodeSSK)clientSSK.getNodeKey());
		if(block == null) return null;
		// Move the pubkey to the top of the LRU, and fix it if it
		// was corrupt.
		cacheKey(clientSSK.pubKeyHash, key);
		return new ClientSSKBlock(block, clientSSK);
	}

	private ClientKeyBlock fetch(ClientCHK clientCHK) throws CHKVerifyException {
		CHKBlock block = fetch(clientCHK.getNodeCHK());
		if(block == null) return null;
		return new ClientCHKBlock(block, clientCHK);
	}
	
	public FCPServer getFCPServer() {
		return fcpServer;
	}

	public void setToadletContainer(SimpleToadletServer server) {
		toadletContainer = server;
	}

	public FproxyToadlet getFproxy() {
		return fproxyServlet;
	}

	public SimpleToadletServer getToadletContainer() {
		return toadletContainer;
	}
	
	public TextModeClientInterfaceServer getTextModeClientInterface(){
		return tmci;
	}

	public void setFproxy(FproxyToadlet fproxy) {
		this.fproxyServlet = fproxy;
	}

	public void setFCPServer(FCPServer fcp) {
		this.fcpServer = fcp;
	}
	
	public void exit(){
		config.store();
        System.out.println("Goodbye.");
        System.exit(0);
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

	public PeerNode[] getDarknetConnections() {
		return peers.myPeers;
	}
	
	public boolean addDarknetConnection(PeerNode pn) {
		boolean retval = peers.addPeer(pn);
		peers.writePeers();
		return retval;
	}
	
	public void removeDarknetConnection(PeerNode pn) {
		peers.disconnect(pn);
	}

	public File getDownloadDir() {
		return downloadDir;
	}
}
