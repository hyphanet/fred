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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;

import freenet.client.ArchiveManager;
import freenet.client.HighLevelSimpleClient;
import freenet.client.HighLevelSimpleClientImpl;
import freenet.client.async.ClientRequestScheduler;
import freenet.clients.http.FproxyToadlet;
import freenet.clients.http.SimpleToadletServer;
import freenet.crypt.DSAPublicKey;
import freenet.crypt.DiffieHellman;
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
import freenet.keys.NodeCHK;
import freenet.keys.NodeSSK;
import freenet.keys.SSKBlock;
import freenet.keys.SSKVerifyException;
import freenet.node.fcp.FCPServer;
import freenet.store.BerkeleyDBFreenetStore;
import freenet.store.FreenetStore;
import freenet.support.BucketFactory;
import freenet.support.FileLoggerHook;
import freenet.support.HexUtil;
import freenet.support.ImmutableByteArrayWrapper;
import freenet.support.LRUHashtable;
import freenet.support.LRUQueue;
import freenet.support.Logger;
import freenet.support.PaddedEphemerallyEncryptedBucketFactory;
import freenet.support.SimpleFieldSet;
import freenet.support.io.FilenameGenerator;
import freenet.support.io.TempBucketFactory;
import freenet.transport.IPAddressDetector;

/**
 * @author amphibian
 */
public class Node {
    
	static final long serialVersionUID = -1;
	
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
    public static final long SUB_MAX_PING_TIME = 1000;
    /** Maximum overall average ping time. If ping is greater than this,
     * we reject all requests.
     */
    public static final long MAX_PING_TIME = 2000;
    /** Accept one request every 10 seconds regardless, to ensure we update the
     * block send time.
     */
    public static final int MAX_INTERREQUEST_TIME = 10*1000;

    // 900ms
    static final int MIN_INTERVAL_BETWEEN_INCOMING_SWAP_REQUESTS = 900;
    public static final int SYMMETRIC_KEY_LENGTH = 32; // 256 bits - note that this isn't used everywhere to determine it
    
    // FIXME: abstract out address stuff? Possibly to something like NodeReference?
    final int portNumber;
    
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
    
    private final HashSet runningUIDs;
    
    byte[] myIdentity; // FIXME: simple identity block; should be unique
    /** Hash of identity. Used as setup key. */
    byte[] identityHash;
    /** Hash of hash of identity i.e. hash of setup key. */
    byte[] identityHashHash; 
    String myName;
    final LocationManager lm;
    final PeerManager peers; // my peers
    public final RandomSource random; // strong RNG
    final UdpSocketManager usm;
    final FNPPacketMangler packetMangler;
    final PacketSender ps;
    final NodeDispatcher dispatcher;
    final NodePinger nodePinger;
    final String filenamesPrefix;
    final FilenameGenerator tempFilenameGenerator;
    final FileLoggerHook fileLoggerHook;
    static final int MAX_CACHED_KEYS = 1000;
    final LRUHashtable cachedPubKeys;
    final boolean testnetEnabled;
    final int testnetPort;
    static short MAX_HTL = 10;
    static final int EXIT_STORE_FILE_NOT_FOUND = 1;
    static final int EXIT_STORE_IOEXCEPTION = 2;
    static final int EXIT_STORE_OTHER = 3;
    static final int EXIT_USM_DIED = 4;
    public static final int EXIT_YARROW_INIT_FAILED = 5;
    static final int EXIT_TEMP_INIT_ERROR = 6;
    static final int EXIT_TESTNET_FAILED = 7;
    public static final int EXIT_MAIN_LOOP_LOST = 8;
    
    public final long bootID;
    public final long startupTime;
    
    // Client stuff
    final ArchiveManager archiveManager;
    public final BucketFactory tempBucketFactory;
    final RequestThrottle requestThrottle;
    final RequestStarter requestStarter;
    final RequestThrottle insertThrottle;
    final RequestStarter insertStarter;
    final File downloadDir;
    final TestnetHandler testnetHandler;
    final TestnetStatusUploader statusUploader;
    public final ClientRequestScheduler fetchScheduler;
    public final ClientRequestScheduler putScheduler;
    
    // Client stuff that needs to be configged - FIXME
    static final int MAX_ARCHIVE_HANDLERS = 200; // don't take up much RAM... FIXME
    static final long MAX_CACHED_ARCHIVE_DATA = 32*1024*1024; // make a fixed fraction of the store by default? FIXME
    static final long MAX_ARCHIVE_SIZE = 1024*1024; // ??? FIXME
    static final long MAX_ARCHIVED_FILE_SIZE = 1024*1024; // arbitrary... FIXME
    static final int MAX_CACHED_ELEMENTS = 1024; // equally arbitrary! FIXME hopefully we can cache many of these though
    
    /**
     * Read all storable settings (identity etc) from the node file.
     * @param filename The name of the file to read from.
     */
    private void readNodeFile(String filename) throws IOException {
    	// REDFLAG: Any way to share this code with NodePeer?
        FileInputStream fis = new FileInputStream(filename);
        InputStreamReader isr = new InputStreamReader(fis);
        BufferedReader br = new BufferedReader(isr);
        SimpleFieldSet fs = new SimpleFieldSet(br);
        br.close();
        // Read contents
        String physical = fs.get("physical.udp");
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
        // FIXME: we ignore the IP for now, and hardcode it to localhost
        String identity = fs.get("identity");
        if(identity == null)
            throw new IOException();
        myIdentity = HexUtil.hexToBytes(identity);
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
    }

    private String newName() {
        return "Node created around "+System.currentTimeMillis();
    }

    public void writeNodeFile() {
        try {
            writeNodeFile(filenamesPrefix+"node-"+portNumber, filenamesPrefix+"node-"+portNumber+".bak");
        } catch (IOException e) {
            Logger.error(this, "Cannot write node file!: "+e+" : "+"node-"+portNumber);
        }
    }
    
    private void writeNodeFile(String filename, String backupFilename) throws IOException {
        SimpleFieldSet fs = exportFieldSet();
        File orig = new File(filename);
        File backup = new File(backupFilename);
        orig.renameTo(backup);
        FileOutputStream fos = new FileOutputStream(filename);
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
    }

    /**
     * Read the port number from the arguments.
     * Then create a node.
     */
    public static void main(String[] args) throws IOException {
    	int length = args.length;
    	if (length < 1 || length > 3) {
    		System.out.println("Usage: $ java freenet.node.Node <portNumber> [ipOverride] [max data packets / second]");
    		return;
    	}
    	
        int port = Integer.parseInt(args[0]);
        System.out.println("Port number: "+port);
        File logDir = new File("logs-"+port);
        logDir.mkdir();
        FileLoggerHook logger = new FileLoggerHook(true, new File(logDir, "freenet-"+port).getAbsolutePath(), 
        		"d (c, t, p): m", "MMM dd, yyyy HH:mm:ss:SSS", Logger.MINOR, false, true, 
        		1024*1024*1024 /* 1GB of old compressed logfiles */);
        logger.setInterval("5MINUTES");
        Logger.setupChain();
        Logger.globalSetThreshold(Logger.MINOR);
        Logger.globalAddHook(logger);
        logger.start();
        Logger.normal(Node.class, "Creating node...");
        Yarrow yarrow = new Yarrow();
        InetAddress overrideIP = null;
        int packetsPerSecond = 15;
        if(args.length > 1) {
            overrideIP = InetAddress.getByName(args[1]);
            System.err.println("Overriding IP detection: "+overrideIP.getHostAddress());
            if(args.length > 2) {
            	packetsPerSecond = Integer.parseInt(args[2]);
            }
        }
        DiffieHellman.init(yarrow);
        Node n = new Node(port, yarrow, overrideIP, "", 1000 / packetsPerSecond, true, logger, 16384);
        n.start(new StaticSwapRequestInterval(2000));
        new TextModeClientInterface(n);
        Thread t = new Thread(new MemoryChecker(), "Memory checker");
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
        SimpleToadletServer server = new SimpleToadletServer(port+2000);
        FproxyToadlet fproxy = new FproxyToadlet(n.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS));
        server.register(fproxy, "/", false);
        System.out.println("Starting fproxy on port "+(port+2000));
        new FCPServer(port+3000, n);
        System.out.println("Starting FCP server on port "+(port+3000));
        //server.register(fproxy, "/SSK@", false);
        //server.register(fproxy, "/KSK@", false);
    }
    
    // FIXME - the whole overrideIP thing is a hack to avoid config
    // Implement the config!
    Node(int port, RandomSource rand, InetAddress overrideIP, String prefix, int throttleInterval, boolean enableTestnet, FileLoggerHook logger, int maxStoreKeys) {
    	this.fileLoggerHook = logger;
    	cachedPubKeys = new LRUHashtable();
    	if(enableTestnet) {
    		Logger.error(this, "WARNING: ENABLING TESTNET CODE! This may seriously jeopardize your anonymity!");
    		testnetEnabled = true;
    		testnetPort = 1024 + (port-1024+1000) % (65536 - 1024);
    		testnetHandler = new TestnetHandler(this, testnetPort);
    		statusUploader = new TestnetStatusUploader(this, 180000);
    	} else {
    		testnetEnabled = false;
    		testnetPort = -1;
    		testnetHandler = null;
    		statusUploader = null;
    	}
        portNumber = port;
        startupTime = System.currentTimeMillis();
        recentlyCompletedIDs = new LRUQueue();
        ipDetector = new IPAddressDetector(10*1000, this);
        if(prefix == null) prefix = "";
        filenamesPrefix = prefix;
        this.overrideIPAddress = overrideIP;
        downloadDir = new File("downloads");
        downloadDir.mkdir();
        try {
            chkDatastore = new BerkeleyDBFreenetStore(prefix+"store-"+portNumber, maxStoreKeys, 32768, CHKBlock.TOTAL_HEADERS_LENGTH);
            sskDatastore = new BerkeleyDBFreenetStore(prefix+"sskstore-"+portNumber, maxStoreKeys, 1024, SSKBlock.TOTAL_HEADERS_LENGTH);
            pubKeyDatastore = new BerkeleyDBFreenetStore(prefix+"pubkeystore-"+portNumber, maxStoreKeys, DSAPublicKey.PADDED_SIZE, 0);
        } catch (FileNotFoundException e1) {
            Logger.error(this, "Could not open datastore: "+e1, e1);
            System.err.println("Could not open datastore: "+e1);
            System.exit(EXIT_STORE_FILE_NOT_FOUND);
            throw new Error();
        } catch (IOException e1) {
            Logger.error(this, "Could not open datastore: "+e1, e1);
            System.err.println("Could not open datastore: "+e1);
            System.exit(EXIT_STORE_IOEXCEPTION);
            throw new Error();
        } catch (Exception e1) {
            Logger.error(this, "Could not open datastore: "+e1, e1);
            System.err.println("Could not open datastore: "+e1);
            System.exit(EXIT_STORE_OTHER);
            throw new Error();
        }
        random = rand;
        requestSenders = new HashMap();
        transferringRequestSenders = new HashMap();
        insertSenders = new HashMap();
        runningUIDs = new HashSet();

        BlockTransmitter.setMinPacketInterval(throttleInterval);

        /*
         * FIXME: test the soft limit.
         * 
         * The soft limit is implemented, except for:
         * - We need to write the current status to disk every 1 minute or so.
         * - When we start up, we need to read this in, assume that the node sent
         *   as many packets as it was allowed to in the following minute, and
         *   then shut down before writing again (worst case scenario).
         * - We need to test the soft limit!
         */
        BlockTransmitter.setSoftLimitPeriod(14*24*60*60*1000);
        BlockTransmitter.setSoftMinPacketInterval(0);
        
		lm = new LocationManager(random);

        try {
        	readNodeFile(prefix+"node-"+portNumber);
        } catch (IOException e) {
            try {
                readNodeFile(prefix+"node-"+portNumber+".bak");
            } catch (IOException e1) {
                initNodeFileSettings(random);
            }
        }
        writeNodeFile();
        
        ps = new PacketSender(this);
        peers = new PeerManager(this, prefix+"peers-"+portNumber);
        
        try {
            usm = new UdpSocketManager(portNumber);
            usm.setDispatcher(dispatcher=new NodeDispatcher(this));
            usm.setLowLevelFilter(packetMangler = new FNPPacketMangler(this));
        } catch (SocketException e2) {
            Logger.error(this, "Could not listen for traffic: "+e2, e2);
            System.exit(EXIT_USM_DIED);
            throw new Error();
        }
        decrementAtMax = random.nextDouble() <= DECREMENT_AT_MAX_PROB;
        decrementAtMin = random.nextDouble() <= DECREMENT_AT_MIN_PROB;
        bootID = random.nextLong();
        peers.writePeers();
        try {
        	String dirName = "temp-"+portNumber;
			tempFilenameGenerator = new FilenameGenerator(random, true, new File(dirName), "temp-");
		} catch (IOException e) {
			Logger.error(this, "Could not create temp bucket factory: "+e, e);
			System.exit(EXIT_TEMP_INIT_ERROR);
			throw new Error();
		}
        nodePinger = new NodePinger(this);
		tempBucketFactory = new PaddedEphemerallyEncryptedBucketFactory(new TempBucketFactory(tempFilenameGenerator), random, 1024);
		archiveManager = new ArchiveManager(MAX_ARCHIVE_HANDLERS, MAX_CACHED_ARCHIVE_DATA, MAX_ARCHIVE_SIZE, MAX_ARCHIVED_FILE_SIZE, MAX_CACHED_ELEMENTS, random, tempFilenameGenerator);
		requestThrottle = new RequestThrottle(5000, 2.0F);
		requestStarter = new RequestStarter(this, requestThrottle, "Request starter ("+portNumber+")");
		fetchScheduler = new ClientRequestScheduler(false, random, requestStarter);
		requestStarter.setScheduler(fetchScheduler);
		requestStarter.start();
		//insertThrottle = new ChainedRequestThrottle(10000, 2.0F, requestThrottle);
		// FIXME reenable the above
		insertThrottle = new RequestThrottle(10000, 2.0F);
		insertStarter = new RequestStarter(this, insertThrottle, "Insert starter ("+portNumber+")");
		putScheduler = new ClientRequestScheduler(true, random, insertStarter);
		insertStarter.setScheduler(putScheduler);
		insertStarter.start();
		if(testnetHandler != null)
			testnetHandler.start();
		if(statusUploader != null)
			statusUploader.start();
		System.err.println("Created Node on port "+port);
    }

    void start(SwapRequestInterval interval) {
        if(interval != null)
            lm.startSender(this, interval);
        ps.start();
        usm.start();
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
        		requestThrottle.requestRejectedOverload();
        		rejectedOverload = true;
        	}

        	int status = rs.getStatus();
        	
        	if(status == RequestSender.NOT_FINISHED) 
        		continue;
        	
        	if(status == RequestSender.TIMED_OUT ||
        			status == RequestSender.GENERATED_REJECTED_OVERLOAD) {
        		if(!rejectedOverload) {
            		requestThrottle.requestRejectedOverload();
        			rejectedOverload = true;
        		}
        	} else {
        		if(status == RequestSender.DATA_NOT_FOUND ||
        				status == RequestSender.SUCCESS ||
        				status == RequestSender.ROUTE_NOT_FOUND ||
        				status == RequestSender.VERIFY_FAILURE) {
        			long rtt = System.currentTimeMillis() - startTime;
        			requestThrottle.requestCompleted(rtt);
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
        		requestThrottle.requestRejectedOverload();
        		rejectedOverload = true;
        	}

        	int status = rs.getStatus();
        	
        	if(status == RequestSender.NOT_FINISHED) 
        		continue;
        	
        	if(status == RequestSender.TIMED_OUT ||
        			status == RequestSender.GENERATED_REJECTED_OVERLOAD) {
        		if(!rejectedOverload) {
            		requestThrottle.requestRejectedOverload();
        			rejectedOverload = true;
        		}
        	} else {
        		if(status == RequestSender.DATA_NOT_FOUND ||
        				status == RequestSender.SUCCESS ||
        				status == RequestSender.ROUTE_NOT_FOUND ||
        				status == RequestSender.VERIFY_FAILURE) {
        			long rtt = System.currentTimeMillis() - startTime;
        			requestThrottle.requestCompleted(rtt);
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
    			insertThrottle.requestRejectedOverload();
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
    			insertThrottle.requestRejectedOverload();
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
        		insertThrottle.requestCompleted(len);
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
        byte[] data = block.getRawData();
        byte[] headers = block.getRawHeaders();
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
    			insertThrottle.requestRejectedOverload();
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
        		long len = endTime - startTime;
        		insertThrottle.requestCompleted(len);
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
    	lastAcceptedRequest = now;
    	return false;
    }
    
    /**
     * Export my reference so that another node can connect to me.
     * @return
     */
    public SimpleFieldSet exportFieldSet() {
        SimpleFieldSet fs = new SimpleFieldSet();
        fs.put("physical.udp", getPrimaryIPAddress().getHostAddress()+":"+portNumber);
        fs.put("identity", HexUtil.bytesToHex(myIdentity));
        fs.put("location", Double.toString(lm.getLocation().getValue()));
        fs.put("version", Version.getVersionString());
        fs.put("testnet", Boolean.toString(testnetEnabled));
        fs.put("lastGoodVersion", Version.getLastGoodVersionString());
        if(testnetEnabled)
        	fs.put("testnetPort", Integer.toString(testnetPort));
        fs.put("myName", myName);
        Logger.minor(this, "My reference: "+fs);
        return fs;
    }

    InetAddress overrideIPAddress;
    
    /**
     * @return Our current main IP address.
     * FIXME - we should support more than 1, and we should do the
     * detection properly with NetworkInterface, and we should use
     * third parties if available and UP&P if available.
     */
    private InetAddress getPrimaryIPAddress() {
        if(overrideIPAddress != null) {
            Logger.minor(this, "Returning overridden address: "+overrideIPAddress);
            return overrideIPAddress;
        }
        Logger.minor(this, "IP address not overridden");
       	return ipDetector.getAddress();
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
        			Logger.minor(this, "Got pubkey: "+pubKey+" "+(pubKey == null ? "" : pubKey.writeAsField()));
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
    	return sb.toString();
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
    public byte[] myRefCompressed() {
        SimpleFieldSet fs = exportFieldSet();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        OutputStreamWriter osw = new OutputStreamWriter(baos);
        try {
            fs.writeTo(osw);
        } catch (IOException e) {
            throw new Error(e);
        }
        try {
            osw.flush();
        } catch (IOException e1) {
            throw new Error(e1);
        }
        byte[] buf = baos.toByteArray();
        byte[] obuf = new byte[buf.length + 1];
        obuf[0] = 0;
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

    public synchronized void setName(String key) {
        myName = key;
        writeNodeFile();
    }

	public HighLevelSimpleClient makeClient(short prioClass) {
		return new HighLevelSimpleClientImpl(this, archiveManager, tempBucketFactory, random, !DONT_CACHE_LOCAL_REQUESTS, prioClass);
	}
	
	private static class MemoryChecker implements Runnable {

		public void run() {
			Runtime r = Runtime.getRuntime();
			while(true) {
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					// Ignore
				}
				Logger.minor(this, "Memory in use: "+(r.totalMemory()-r.freeMemory()));
			}
		}
	}

	public RequestThrottle getRequestThrottle() {
		return requestThrottle;
	}

	public RequestThrottle getInsertThrottle() {
		return insertThrottle;
	}

	InetAddress lastIP;
	
	public void redetectAddress() {
		InetAddress newIP = ipDetector.getAddress();
		if(newIP.equals(lastIP)) return;
		writeNodeFile();
	}
	
	/**
	 * Look up a cached public key by its hash.
	 */
	public DSAPublicKey getKey(byte[] hash) {
		ImmutableByteArrayWrapper w = new ImmutableByteArrayWrapper(hash);
		synchronized(cachedPubKeys) {
			DSAPublicKey key = (DSAPublicKey) cachedPubKeys.get(w);
			if(key != null) {
				cachedPubKeys.push(w, key);
				return key;
			}
		}
		try {
			DSAPublicKey key = pubKeyDatastore.fetchPubKey(hash, false);
			if(key != null) {
				cacheKey(hash, key);
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
}
