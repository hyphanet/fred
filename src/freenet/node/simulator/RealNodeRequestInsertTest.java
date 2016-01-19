/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.simulator;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.ArrayList;
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
import freenet.node.FSParseException;
import freenet.node.LowLevelGetException;
import freenet.node.Node;
import freenet.node.NodeInitException;
import freenet.node.NodeStarter;
import freenet.node.NodeStarter.TestNodeParameters;
import freenet.node.NodeStarter.TestingVMBypass;
import freenet.node.PeerNode;
import freenet.support.Executor;
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

    static final int NUMBER_OF_NODES = 100;
    static final int DEGREE = 10;
    static final short MAX_HTL = (short)5;
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
    static final TestingVMBypass BYPASS_TRANSPORT_LAYER = TestingVMBypass.FAST_QUEUE_BYPASS;
    static final int PACKET_DROP = 0;
    
    static final int TARGET_SUCCESSES = 20;
    //static final int NUMBER_OF_NODES = 50;
    //static final short MAX_HTL = 10;

    // FIXME: HACK: High bwlimit makes the "other" requests not affect the test requests.
    // Real solution is to get rid of the "other" requests!!
    static final int BWLIMIT = 1000*1024;
    
    //public static final int DARKNET_PORT_BASE = RealNodePingTest.DARKNET_PORT2+1;
    public static final int DARKNET_PORT_BASE = 10000;
    public static final int DARKNET_PORT_END = DARKNET_PORT_BASE + NUMBER_OF_NODES;
    
	public static void main(String[] args) throws FSParseException, PeerParseException, CHKEncodeException, InvalidThresholdException, NodeInitException, ReferenceSignatureVerificationException, InterruptedException {
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
        String logDetails = "";
        NodeStarter.globalTestInit(new File(name), false, LogLevel.ERROR, logDetails, true, 
                BYPASS_TRANSPORT_LAYER, null);
        System.out.println("Insert/retrieve test");
        System.out.println();
        DummyRandomSource nodesRandom = new DummyRandomSource(3142);
        DummyRandomSource random = new DummyRandomSource(3141);
        DummyRandomSource topologyRandom = new DummyRandomSource(3143);
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
            System.err.println("Starting fake connections...");
            for(int i=0;i<NUMBER_OF_NODES;i++) {
                Node n = nodes[i];
                for(PeerNode pnSource : n.getPeerNodes()) {
                    BypassMessageQueue queue = 
                        (BypassMessageQueue) pnSource.getMessageQueue();
                    queue.fakeConnect();
                }
                System.err.println("Started fake connections for node "+i+"/"+nodes.length);
            }
        }
        
        // Wait for all connected *and* average ping is acceptable i.e. CPU load is settled.
        // For NONE, this means wait for connection setup to finish.
        waitForAllConnected(nodes);
        
        if(BYPASS_TRANSPORT_LAYER == TestingVMBypass.NONE) {
            // Wait until we are sure it stabilises.
            waitForPingAverage(0.5, nodes, new DummyRandomSource(3143), MAX_PINGS, 1000);
        }
        
        random = new DummyRandomSource(3144);
        
        System.out.println();
        System.out.println("Ping average > 95%, lets do some inserts/requests");
        System.out.println();
        
        RealNodeRequestInsertTest tester = new RealNodeRequestInsertTest(nodes, random, TARGET_SUCCESSES, tracker, overallUIDTagCounter);
        
        while(true) {
            try {
    			waitForAllConnected(nodes);
    			int status = tester.insertRequestTest();
    			if(status == -1) continue;
    			System.exit(status);
            } catch (Throwable t) {
                Logger.error(RealNodeRequestInsertTest.class, "Caught "+t, t);
            }
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
	}

    private final Node[] nodes;
    private final RandomSource random;
    private int requestNumber = 0;
    private RunningAverage requestsAvg = new SimpleRunningAverage(100, 0.0);
    private String baseString = "Test ";
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
        try {
            Thread.sleep(100);
        } catch (InterruptedException e1) {
        }
        String dataString = baseString + requestNumber;
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
        System.err.println("Created random test key "+testKey+" = "+fetchKey.getNodeKey(false));
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
		tracker.dumpKey(block.getKey(), true, true);
        // Pick random node to request from
        int node2;
        do {
            node2 = random.nextInt(NUMBER_OF_NODES);
        } while(node2 == node1);
        Node fetchNode = nodes[node2];
        try {
        	block = fetchNode.clientCore.realGetKey(fetchKey, false, false, false, REAL_TIME_FLAG);
        } catch (LowLevelGetException e) {
        	block = null;
        }
        tracker.dumpKey(lowLevelKey, false, true);
        if(block == null) {
			int percentSuccess=100*fetchSuccesses/insertAttempts;
            Logger.error(RealNodeRequestInsertTest.class, "Fetch #"+requestNumber+" FAILED ("+percentSuccess+"%); from "+node2);
            System.err.println("Fetch #"+requestNumber+" FAILED ("+percentSuccess+"%); from "+node2);
            requestsAvg.report(0.0);
        } else {
            byte[] results = block.memoryDecode();
            requestsAvg.report(1.0);
            if(Arrays.equals(results, data)) {
				fetchSuccesses++;
				int percentSuccess=100*fetchSuccesses/insertAttempts;
                Logger.error(RealNodeRequestInsertTest.class, "Fetch #"+requestNumber+" from node "+node2+" succeeded ("+percentSuccess+"%): "+new String(results));
                System.err.println("Fetch #"+requestNumber+" succeeded ("+percentSuccess+"%): \""+new String(results)+'\"');
                if(fetchSuccesses == targetSuccesses) {
                	System.err.println("Succeeded, "+targetSuccesses+" successful fetches");
                	return 0;
                }
            } else {
                Logger.error(RealNodeRequestInsertTest.class, "Returned invalid data!: "+new String(results));
                System.err.println("Returned invalid data!: "+new String(results));
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
        System.err.println(load.toString().trim());
        int totalRunningUIDsAlt = runningUIDsList.size();
        assert(overallUIDTagCounter == null || totalRunningUIDsAlt == 0);
        if(totalRunningUIDsAlt != 0) {
        	System.err.println("Still running UIDs (alt): "+totalRunningUIDsAlt);
        	System.err.println("List of running UIDs: "+Arrays.toString(runningUIDsList.toArray()));
        }
        System.err.println("Surplus requests:");
        tracker.dumpAndClear();
        return -1;
	}
}
