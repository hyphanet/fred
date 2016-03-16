/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.simulator;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;

import freenet.crypt.DummyRandomSource;
import freenet.crypt.RandomSource;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.keys.CHKEncodeException;
import freenet.keys.ClientCHKBlock;
import freenet.keys.ClientKSK;
import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.FreenetURI;
import freenet.keys.InsertableClientSSK;
import freenet.keys.Key;
import freenet.keys.KeyDecodeException;
import freenet.keys.SSKEncodeException;
import freenet.node.BypassMessageQueue;
import freenet.node.BypassPacketFormat;
import freenet.node.FSParseException;
import freenet.node.LowLevelGetException;
import freenet.node.Node;
import freenet.node.NodeInitException;
import freenet.node.NodeStarter;
import freenet.node.NodeStarter.TestNodeParameters;
import freenet.node.NodeStarter.TestingVMBypass;
import freenet.node.PeerNode;
import freenet.node.simulator.SimulatorRequestTracker.Request;
import freenet.support.Executor;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.PooledExecutor;
import freenet.support.Logger.LogLevel;
import freenet.support.LoggerHook.InvalidThresholdException;
import freenet.support.PrioritizedTicker;
import freenet.support.compress.Compressor.COMPRESSOR_TYPE;
import freenet.support.compress.InvalidCompressionCodecException;
import freenet.support.io.ArrayBucket;
import freenet.support.io.FileUtil;
import freenet.support.math.RunningAverage;
import freenet.support.math.SimpleRunningAverage;

/**
 * @author amphibian
 */
public class RealNodeRequestInsertTest extends RealNodeRoutingTest {

    static int NUMBER_OF_NODES = 100;
    static int DEGREE = 10;
    static short MAX_HTL = (short)5;
    static final boolean START_WITH_IDEAL_LOCATIONS = true;
    static final boolean FORCE_NEIGHBOUR_CONNECTIONS = true;
    static final boolean ENABLE_SWAPPING = false;
    static final boolean ENABLE_ULPRS = false;
    static final boolean ENABLE_PER_NODE_FAILURE_TABLES = false;
    static final boolean ENABLE_SWAP_QUEUEING = false;
    static final boolean ENABLE_PACKET_COALESCING = true;
    static final boolean ENABLE_FOAF = true;
    /* On a real, large network, we cache only at HTL well below the maximum, for security reasons.
     * For a simulation, this is problematic! Two solutions for smaller networks:
     * 
     * 1) Enable CACHE_HIGH_HTL. Nodes cache everything regardless of HTL. This is the simplest
     * solution but not realistic.
     * 2) Keep the default caching behaviour and enable FORK_ON_CACHEABLE. On a small network, the
     * nodes closest to the destination may have already been visited by the time HTL is small 
     * enough for the data to be cached. FORK_ON_CACHEABLE allows the insert to go back to these 
     * nodes if necessary. 
     * 
     * You should enable one of the following two options:
     */
    /** Cache all requested/inserted blocks regardless of HTL ("write local to datastore"). */
    static final boolean CACHE_HIGH_HTL = true;
    /** Fork inserts to a new UID after they become cacheable, so that the insert can go back to 
     * the nodes its already visited (but wouldn't have been cached on). */
    static final boolean FORK_ON_CACHEABLE = false;
    static final boolean DISABLE_PROBABILISTIC_HTLS = true;
    // Set to true to cache everything. This depends on security level.
    static final boolean USE_SLASHDOT_CACHE = false;
    static final boolean REAL_TIME_FLAG = false;
    static final boolean DISABLE_RANDOM_REINSERT = true;
    static TestingVMBypass BYPASS_TRANSPORT_LAYER = TestingVMBypass.PACKET_BYPASS;
    static int PACKET_DROP = 0;
    static long SEED = 3141;
    
    static final int TARGET_SUCCESSES = 20;
    //static final int NUMBER_OF_NODES = 50;
    //static final short MAX_HTL = 10;

    // FIXME: HACK: High bwlimit makes the "other" requests not affect the test requests.
    // Real solution is to get rid of the "other" requests!!
    static int BWLIMIT = 1000*1024;
    // Bandwidth limit *per connection* for CBR bypass.
    static int CBR_BWLIMIT = 1000;
    
    //public static final int DARKNET_PORT_BASE = RealNodePingTest.DARKNET_PORT2+1;
    public static final int DARKNET_PORT_BASE = 10000;
    public static final int DARKNET_PORT_END = DARKNET_PORT_BASE + NUMBER_OF_NODES;
    
    private final HashSet<Key> generatedKeys;
    
	public static void main(String[] args) throws FSParseException, PeerParseException, CHKEncodeException, InvalidThresholdException, NodeInitException, ReferenceSignatureVerificationException, InterruptedException, SimulatorOverloadedException, SSKEncodeException, InvalidCompressionCodecException, IOException, KeyDecodeException {
	    try {
	    parseOptions(args);
        String name = "realNodeRequestInsertTest";
        File wd = new File(name);
        if(!FileUtil.removeAll(wd)) {
        	System.err.println("Mass delete failed, test may not be accurate.");
        	System.exit(EXIT_CANNOT_DELETE_OLD_DATA);
        }
        wd.mkdir();
        //NOTE: globalTestInit returns in ignored random source
        //String logDetails = "freenet.node.Request:MINOR,freenet.node.CHK:MINOR,freenet.node.SSK:MINOR," +
        //		"freenet.io.comm.MessageCore:MINOR,freenet.node.Peer:MINOR,freenet.node.Node:MINOR";
        //String logDetails = "";
        String logDetails = "freenet.node.Bypass:MINOR";
        NodeStarter.globalTestInit(new File(name), false, LogLevel.ERROR, logDetails, true, 
                BYPASS_TRANSPORT_LAYER, null);
        System.out.println("Insert/retrieve test");
        System.out.println();
        System.err.println("Seed is "+SEED);
        DummyRandomSource random = new DummyRandomSource(SEED);
        DummyRandomSource nodesRandom = new DummyRandomSource(SEED+1);
        DummyRandomSource topologyRandom = new DummyRandomSource(SEED+2);
        SimulatorRequestTracker tracker = new SimulatorRequestTracker(MAX_HTL);
        //DiffieHellman.init(random);
        Node[] nodes = new Node[NUMBER_OF_NODES];
        Logger.normal(RealNodeRoutingTest.class, "Creating nodes...");
        int tickerThreads = Runtime.getRuntime().availableProcessors();
        Executor[] executors = new Executor[tickerThreads];
        PrioritizedTicker[] tickers = new PrioritizedTicker[tickerThreads];
        for(int i=0;i<tickerThreads;i++) {
            executors[i] = new PooledExecutor();
            tickers[i] = new PrioritizedTicker(executors[i]);
        }
        final TotalRequestUIDsCounter overallUIDTagCounter =
                new TotalRequestUIDsCounter();
        for(int i=0;i<NUMBER_OF_NODES;i++) {
            TestNodeParameters params = getNodeParameters(i, name, nodesRandom, 
                    executors[i % tickerThreads], tickers[i % tickerThreads], overallUIDTagCounter);
            nodes[i] = NodeStarter.createTestNode(params);
            tracker.add(nodes[i]);
            Logger.normal(RealNodeRoutingTest.class, "Created node "+i);
        }
        
        // Now link them up
        makeKleinbergNetwork(nodes, START_WITH_IDEAL_LOCATIONS, DEGREE, FORCE_NEIGHBOUR_CONNECTIONS, topologyRandom);

        Logger.normal(RealNodeRoutingTest.class, "Added random links");
        
        for(int i=0;i<NUMBER_OF_NODES;i++) {
            nodes[i].start(false);
            System.err.println("Started node "+i+"/"+nodes.length);
        }
        
        if(NodeStarter.isMessageQueueBypassEnabled()) {
            System.err.println("Starting fake connections (message bypass)...");
            for(int i=0;i<NUMBER_OF_NODES;i++) {
                Node n = nodes[i];
                for(PeerNode pnSource : n.getPeerNodes()) {
                    BypassMessageQueue queue = 
                        (BypassMessageQueue) pnSource.getMessageQueue();
                    queue.fakeConnect();
                }
                System.err.println("Started fake connections for node "+i+"/"+nodes.length);
            }
        } else if(NodeStarter.isPacketBypassEnabled()) {
            System.err.println("Starting fake connections (packet bypass)...");
            for(int i=0;i<NUMBER_OF_NODES;i++) {
                Node n = nodes[i];
                for(PeerNode pnSource : n.getPeerNodes()) {
                    BypassPacketFormat bypass =
                        (BypassPacketFormat) pnSource.getPacketFormat();
                    bypass.fakeConnect();
                }
                System.err.println("Started fake connections for node "+i+"/"+nodes.length);
            }
        }
        
        // Wait for all connected *and* average ping is acceptable i.e. CPU load is settled.
        // For NONE, this means wait for connection setup to finish.
        waitForAllConnected(nodes);
        
        if(BYPASS_TRANSPORT_LAYER == TestingVMBypass.NONE) {
            // Wait until we are sure it stabilises.
            waitForPingAverage(0.5, nodes, new DummyRandomSource(SEED+4), MAX_PINGS, 1000);
        }
        
        random = new DummyRandomSource(SEED+3);
        
        System.out.println();
        System.out.println("Ping average > 95%, lets do some inserts/requests");
        System.out.println();
        
        RealNodeRequestInsertTest tester = new RealNodeRequestInsertTest(nodes, random, TARGET_SUCCESSES, tracker, overallUIDTagCounter);
        
        while(true) {
            waitForAllConnected(nodes, true, true);
            int status = tester.insertRequestTest();
            if(status == -1) continue;
            System.exit(status);
        }
        } catch (Throwable t) {
            // Need to explicitly exit because the wrapper thread may prevent shutdown.
            // FIXME WTF? Shouldn't be using the wrapper???
            Logger.error(RealNodeRequestInsertTest.class, "Caught "+t, t);
            System.err.println(t);
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static void parseOptions(String[] args) {
        // FIXME Standard way to do this? Don't want to import a new library for a test...
        for(String s : args) {
            int x = s.indexOf('=');
            if(x == -1) {
                printUsage();
                System.exit(1);
            }
            String arg = s.substring(0, x);
            String value = s.substring(x+1);
            parseArgument(arg, value);
        }
    }

    private static void printUsage() {
        System.err.println("java -cp freenet.jar:freenet-ext.jar:bcprov-*.jar " + RealNodeRequestInsertTest.class.getName() + " arg1=blah arg2=blah ...");
        System.err.println("Arguments:\n");
        System.err.println("size\tNumber of simulated nodes");
        System.err.println("degree\tAverage number of peers per node");
        System.err.println("htl\tMaximum Hops To Live");
        System.err.println("drop\tDrop one in this many packets (0 = no drop)");
        System.err.println("bandwidth\tOutput bandwidth limit per node");
        System.err.println("bandwidth-cbr\tOutput bandwidth limit per connection if using CBR bypass");
        System.err.println("seed\tRNG seed");
        System.err.println("bypass\tVarious possible bypasses:");
        for(TestingVMBypass t : TestingVMBypass.values()) {
            System.err.println("\t" + t.name());
        }
    }

    private static void parseArgument(String arg, String value) {
        arg = arg.toLowerCase();
        if(arg.equals("bypass")) {
            BYPASS_TRANSPORT_LAYER = TestingVMBypass.valueOf(value.toUpperCase());
        } else if(arg.equals("size")) {
            NUMBER_OF_NODES = Integer.parseInt(value);
        } else if(arg.equals("degree")) {
            DEGREE = Integer.parseInt(value);
        } else if(arg.equals("htl")) {
            MAX_HTL = Short.parseShort(value);
        } else if(arg.equals("drop")) {
            PACKET_DROP = Integer.parseInt(value);
        } else if(arg.equals("bandwidth")) {
            BWLIMIT = Integer.parseInt(value);
        } else if(arg.equals("bandwidth-cbr")) {
            CBR_BWLIMIT = Integer.parseInt(value);
        } else if(arg.equals("seed")) {
            SEED = Long.parseLong(value);
        } else {
            printUsage();
            System.exit(2);
        }
    }

    private static TestNodeParameters getNodeParameters(int i, String name, RandomSource nodesRandom,
            Executor executor, PrioritizedTicker ticker, TotalRequestUIDsCounter overallUIDTagCounter) {
        TestNodeParameters params = new TestNodeParameters();
        params.port = DARKNET_PORT_BASE+i;
        params.baseDirectory = new File(name);
        params.disableProbabilisticHTLs = DISABLE_PROBABILISTIC_HTLS;
        params.maxHTL = MAX_HTL;
        params.dropProb = PACKET_DROP;
        params.random = new DummyRandomSource(nodesRandom.nextLong());
        params.executor = executor;
        params.ticker = ticker;
        params.threadLimit = 500*NUMBER_OF_NODES;
        params.storeSize = 256*1024;
        params.ramStore = true;
        params.enableSwapping = ENABLE_SWAPPING;
        params.enableULPRs = ENABLE_ULPRS;
        params.enablePerNodeFailureTables = ENABLE_PER_NODE_FAILURE_TABLES;
        params.enableSwapQueueing = ENABLE_SWAP_QUEUEING;
        params.enablePacketCoalescing = ENABLE_PACKET_COALESCING;
        params.outputBandwidthLimit = BWLIMIT;
        params.longPingTimes = true;
        params.useSlashdotCache = USE_SLASHDOT_CACHE;
        params.writeLocalToDatastore = CACHE_HIGH_HTL;
        params.requestTrackerSnooper = overallUIDTagCounter;
        params.bypassCBRBandwidthLimit = CBR_BWLIMIT;
        if(DISABLE_RANDOM_REINSERT)
            params.randomReinsertInterval = 0;
        return params;
    }

    /**
     * @param nodes
     * @param random
     * @param targetSuccesses
     * @param tracker
     * @param overallUIDTagCounter If not null, we expect all requests to finish after
     * each cycle, and wait if necessary to achieve this. This should make results more
     * reproducible. If null, we log any requests still running after each cycle.
     */
    public RealNodeRequestInsertTest(Node[] nodes, DummyRandomSource random, int targetSuccesses, SimulatorRequestTracker tracker, TotalRequestUIDsCounter overallUIDTagCounter) {
    	this.nodes = nodes;
    	this.random = random;
    	this.targetSuccesses = targetSuccesses;
    	this.tracker = tracker;
    	this.overallUIDTagCounter = overallUIDTagCounter;
    	generatedKeys = new HashSet<Key>();
	}

    private final Node[] nodes;
    private final RandomSource random;
    private int requestNumber = 0;
    private RunningAverage requestsAvg = new SimpleRunningAverage(100, 0.0);
    private String baseString = "Test-";
	private int insertAttempts = 0;
	private int fetchSuccesses = 0;
	private final int targetSuccesses;
	private final SimulatorRequestTracker tracker;
	private final TotalRequestUIDsCounter overallUIDTagCounter;

	/**
	 * @param nodes
	 * @param random
	 * @return -1 to continue or an exit code (0 or positive for an error).
	 * @throws CHKEncodeException
	 * @throws InvalidCompressionCodecException
	 * @throws SSKEncodeException
	 * @throws IOException
	 * @throws KeyDecodeException
	 * @throws InterruptedException 
	 */
	int insertRequestTest() throws CHKEncodeException, InvalidCompressionCodecException, SSKEncodeException, IOException, KeyDecodeException, InterruptedException {
		
        requestNumber++;
        // TEST [number]: [easy to parse but readable, reproducible output]
        String prefix = "TEST "+requestNumber+": ";
        try {
            Thread.sleep(100);
        } catch (InterruptedException e1) {
        }
        byte[] nonce = new byte[8];
        random.nextBytes(nonce);
        String dataString = baseString + requestNumber + "-" + HexUtil.bytesToHex(nonce);
        // Pick random node to insert to
        int node1 = random.nextInt(NUMBER_OF_NODES);
        Node randomNode = nodes[node1];
        //Logger.error(RealNodeRequestInsertTest.class,"Inserting: \""+dataString+"\" to "+node1);
        
        boolean isSSK = requestNumber % 2 == 1;
        
        FreenetURI testKey;
        ClientKey insertKey;
        ClientKey fetchKey;
        ClientKeyBlock block;
        
    	byte[] buf = dataString.getBytes("UTF-8");
        if(isSSK) {
        	testKey = new FreenetURI("KSK", dataString);
        	
        	insertKey = InsertableClientSSK.create(testKey);
        	fetchKey = ClientKSK.create(testKey);
        	
        	block = ((InsertableClientSSK)insertKey).encode(new ArrayBucket(buf), false, false, (short)-1, buf.length, random, COMPRESSOR_TYPE.DEFAULT_COMPRESSORDESCRIPTOR, false);
        } else {
        	block = ClientCHKBlock.encode(buf, false, false, (short)-1, buf.length, COMPRESSOR_TYPE.DEFAULT_COMPRESSORDESCRIPTOR, false);
        	insertKey = fetchKey = block.getClientKey();
        	testKey = insertKey.getURI();
        }
        
        System.err.println();
        System.err.println(prefix+"Created random test key "+testKey);
        Logger.normal(this, "Test key: "+testKey+" = "+fetchKey.getNodeKey(false));
        System.err.println();
        
        byte[] data = dataString.getBytes("UTF-8");
        Logger.minor(RealNodeRequestInsertTest.class, "Decoded: "+new String(block.memoryDecode(), "UTF-8"));
        Logger.normal(RealNodeRequestInsertTest.class,"Insert Key: "+insertKey.getURI());
        Logger.normal(RealNodeRequestInsertTest.class,"Fetch Key: "+fetchKey.getURI());
		try {
			insertAttempts++;
			randomNode.clientCore.realPut(block.getBlock(), false, FORK_ON_CACHEABLE, false, false, REAL_TIME_FLAG);
			Logger.error(RealNodeRequestInsertTest.class, "Inserted to "+node1);
		} catch (freenet.node.LowLevelPutException putEx) {
			Logger.error(RealNodeRequestInsertTest.class, "Insert failed: "+ putEx);
			System.err.println("Insert failed: "+ putEx);
			return EXIT_INSERT_FAILED;
		}
		Key lowLevelKey = block.getKey();
		generatedKeys.add(lowLevelKey);
		Request[] inserts = tracker.dumpKey(block.getKey(), true);
		dumpRequests(inserts, prefix+"INSERT: ");
		if(overallUIDTagCounter != null)
		    overallUIDTagCounter.waitForNoRequests();
        // Pick random node to request from
        int node2;
        do {
            node2 = random.nextInt(NUMBER_OF_NODES);
        } while(node2 == node1);
        Node fetchNode = nodes[node2];
        LowLevelGetException error = null;
        try {
        	block = fetchNode.clientCore.realGetKey(fetchKey, false, false, false, REAL_TIME_FLAG);
        } catch (LowLevelGetException e) {
        	block = null;
        	error = e;
        }
        Request[] requests = tracker.dumpKey(lowLevelKey, false);
        dumpRequests(requests,prefix+"REQUEST: ");
        if(block == null) {
			int percentSuccess=100*fetchSuccesses/insertAttempts;
            Logger.error(RealNodeRequestInsertTest.class, "Fetch #"+requestNumber+" FAILED ("+percentSuccess+"%); from "+node2);
            System.err.println(prefix+"Fetch #"+requestNumber+" FAILED ("+percentSuccess+"%); from "+node2+" : "+LowLevelGetException.getMessage(error.code));
            checkRequestFailure(inserts, requests);
            requestsAvg.report(0.0);
        } else {
            byte[] results = block.memoryDecode();
            requestsAvg.report(1.0);
            if(Arrays.equals(results, data)) {
				fetchSuccesses++;
				int percentSuccess=100*fetchSuccesses/insertAttempts;
                Logger.error(RealNodeRequestInsertTest.class, "Fetch #"+requestNumber+" from node "+node2+" succeeded ("+percentSuccess+"%): "+new String(results));
                System.err.println(prefix+"Fetch #"+requestNumber+" succeeded ("+percentSuccess+"%): \""+new String(results)+'\"');
                if(fetchSuccesses == targetSuccesses) {
                	System.err.println("Succeeded, "+targetSuccesses+" successful fetches");
                	return 0;
                }
                checkRequestSuccess(inserts, requests);
            } else {
                Logger.error(RealNodeRequestInsertTest.class, "Returned invalid data!: "+new String(results));
                System.err.println(prefix+"Returned invalid data!: "+new String(results));
                return EXIT_BAD_DATA;
            }
        }
        if(overallUIDTagCounter != null) {
            // We expect that there are no requests running.
            if(isSSK) {
                // Will have finished already.
                assert(this.overallUIDTagCounter.getCount() == 0);
            } else {
                // CHK requests can take some time to finish due to path folding (even if disabled).
                overallUIDTagCounter.waitForNoRequests();
            }
        } else {
            // There may be requests running.
        }
        StringBuilder load = new StringBuilder("Running UIDs for nodes: ");
        TreeSet<Long> runningUIDsList = new TreeSet<Long>();
        List<Long> tempRunning = new ArrayList<Long>();
        for(int i=0;i<nodes.length;i++) {
        	tempRunning.clear();
        	nodes[i].tracker.addRunningUIDs(tempRunning);
        	int runningUIDsAlt = tempRunning.size();
        	for(Long l : tempRunning) runningUIDsList.add(l);
        	if(runningUIDsAlt != 0) {
        	    load.append(i);
        	    load.append(':');
        	    load.append(runningUIDsAlt);
                load.append(' ');
        	}
        }
        int totalRunningUIDsAlt = runningUIDsList.size();
        assert(overallUIDTagCounter == null || totalRunningUIDsAlt == 0);
        if(totalRunningUIDsAlt != 0) {
            System.err.println(load.toString().trim());
        	System.err.println("Still running UIDs (alt): "+totalRunningUIDsAlt);
        	System.err.println("List of running UIDs: "+Arrays.toString(runningUIDsList.toArray()));
        }
        Request[] surplus = tracker.dumpAndClear();
        boolean anySurplus = false;
        System.err.println("Surplus requests:");
        for(Request req : surplus) {
            if(generatedKeys.contains(req.key)) {
                if(req.isInsert) {
                    if(!DISABLE_RANDOM_REINSERT) {
                        Logger.normal(this, "Still running, possibly random reinsert: "+req.dump(false, ""));
                        continue; // Reinsert, ignore.
                    }
                    System.err.println("Old key insert/request still running?:\n"+req.dump(false, prefix+" SURPLUS: "));
                } else {
                    System.err.println("Unknown surplus key:\n"+req.dump(false, prefix+" SURPLUS: "));
                }
                anySurplus = true;
            }
        }
        
        if(anySurplus && shouldBeNoOtherRequests()) {
            // FIXME convert to an assert() when move simulator into test/.
            fail("Should be no surplus requests");
        }
        return -1;
	}

    private boolean shouldBeNoOtherRequests() {
        return DISABLE_RANDOM_REINSERT;
    }

    private void checkRequestSuccess(Request[] inserts, Request[] requests) {
        if(requests.length == 0) {
            // It found the data on the first node, so there were no messages.
            return;
        }
        if(inserts.length == 0) fail("Must be some inserts!");
        // Could be more than one request if fork on cacheable is enabled.
        // FIXME Full support for fork-on-cacheable would require knowing which request
        // is the post-fork request.
        boolean foundAtEnd = false;
        if(!FORK_ON_CACHEABLE) {
            for(Request insert : inserts) {
                int length = insert.nodeIDsVisited.size();
                if(length < MAX_HTL)
                    fail("Insert path too short");
            }
        }
        for(Request request : requests) {
            int length = request.nodeIDsVisited.size();
            for(int i=0;i<length;i++) {
                int node = request.nodeIDsVisited.get(i);
                boolean onInsertPath = false;
                for(Request insert : inserts) {
                    onInsertPath |= insert.containsNode(node);
                }
                if(onInsertPath) {
                    if(i == length-1) {
                        foundAtEnd = true;
                    } else if(!FORK_ON_CACHEABLE) {
                        // Should have found the data!
                        fail("Should have found the data on node "+node);
                    }
                }
            }
        }
        if(!foundAtEnd)
            fail("Data not found at end of request path");
    }

    private void checkRequestFailure(Request[] inserts, Request[] requests) {
        if(requests.length == 0) fail("Must be some requests!");
        if(inserts.length == 0) fail("Must be some inserts!");
        if(FORK_ON_CACHEABLE) return;
        for(Request insert : inserts) {
            int length = insert.nodeIDsVisited.size();
            if(length < MAX_HTL)
                fail("Insert path too short");
        }
        for(Request request : requests) {
            int length = request.nodeIDsVisited.size();
            if(length < MAX_HTL)
                fail("Request path too short");
            for(int i=0;i<length;i++) {
                int node = request.nodeIDsVisited.get(i);
                for(Request insert : inserts) {
                    if(insert.containsNode(node))
                        fail("Should have found the data on node "+node);
                }
            }
        }
    }

    private void fail(String message) {
        // FIXME throw new AssertionError(message)
        // FIXME Change to assertions when moved to test/
        throw new RuntimeException(message);
    }

    private void dumpRequests(Request[] requests, String prefix) {
        for(Request req : requests) {
            String msg = req.dump(true, prefix);
            Logger.normal(this, msg);
            System.err.println(msg);
        }
    }
}
