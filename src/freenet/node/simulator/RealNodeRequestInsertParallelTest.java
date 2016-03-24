/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.simulator;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.HashSet;

import freenet.crypt.DSAPublicKey;
import freenet.crypt.DummyRandomSource;
import freenet.crypt.RandomSource;
import freenet.keys.CHKBlock;
import freenet.keys.CHKEncodeException;
import freenet.keys.ClientCHKBlock;
import freenet.keys.ClientKeyBlock;
import freenet.keys.FreenetURI;
import freenet.keys.InsertableClientSSK;
import freenet.keys.Key;
import freenet.keys.SSKBlock;
import freenet.keys.SSKEncodeException;
import freenet.node.Node;
import freenet.node.NodeStarter.TestNodeParameters;
import freenet.node.NodeStarter.TestingVMBypass;
import freenet.node.simulator.SimulatorRequestTracker.Request;
import freenet.support.Executor;
import freenet.support.HexUtil;
import freenet.support.PrioritizedTicker;
import freenet.support.compress.Compressor.COMPRESSOR_TYPE;
import freenet.support.compress.InvalidCompressionCodecException;
import freenet.support.io.ArrayBucket;
import freenet.support.math.RunningAverage;
import freenet.support.math.SimpleRunningAverage;
import freenet.support.math.SimpleSampleStatistics;

/**
 * @author amphibian
 */
public abstract class RealNodeRequestInsertParallelTest extends RealNodeRoutingTest {

    static int NUMBER_OF_NODES = 25;
    static int DEGREE = 5;
    /** Number of requests to run in parallel */
    static final int PARALLEL_REQUESTS = 5;
    /** Insert the key this far in advance */
    static final int PREINSERT_GAP = PARALLEL_REQUESTS;
    /** Ignore data before this point */
    static final int PROLOG_SIZE = PARALLEL_REQUESTS*4;
    /** Total number of requests to run */
    static final int TOTAL_REQUESTS = 1000;
    static short MAX_HTL = (short)4;
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
    static TestingVMBypass BYPASS_TRANSPORT_LAYER = TestingVMBypass.NONE;
    static int PACKET_DROP = 0;
    static long SEED = 3141;
    
    static final int TARGET_SUCCESSES = 20;
    //static final int NUMBER_OF_NODES = 50;
    //static final short MAX_HTL = 10;

    // FIXME: HACK: High bwlimit makes the "other" requests not affect the test requests.
    // Real solution is to get rid of the "other" requests!!
    static int BWLIMIT = 10*1024;
    // Bandwidth limit *per connection* for CBR bypass.
    static int CBR_BWLIMIT = 1000;
    
    //public static final int DARKNET_PORT_BASE = RealNodePingTest.DARKNET_PORT2+1;
    public static final int DARKNET_PORT_BASE = 10000;
    public static final int DARKNET_PORT_END = DARKNET_PORT_BASE + NUMBER_OF_NODES;
    
    private final HashSet<Key> generatedKeys;
    
    protected static void parseOptions(String[] args) {
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
        System.err.println("java -cp freenet.jar:freenet-ext.jar:bcprov-*.jar " + RealNodeRequestInsertParallelTest.class.getName() + " arg1=blah arg2=blah ...");
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

    protected static TestNodeParameters getNodeParameters(int i, String name, RandomSource nodesRandom,
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
        int blockSize = (CHKBlock.DATA_LENGTH+CHKBlock.TOTAL_HEADERS_LENGTH+
                SSKBlock.DATA_LENGTH+SSKBlock.TOTAL_HEADERS_LENGTH+DSAPublicKey.PADDED_SIZE);
        params.storeSize = (PARALLEL_REQUESTS+PREINSERT_GAP+1)*blockSize*2;
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
    public RealNodeRequestInsertParallelTest(Node[] nodes, DummyRandomSource random, int targetSuccesses, SimulatorRequestTracker tracker, TotalRequestUIDsCounter overallUIDTagCounter) {
    	this.nodes = nodes;
    	this.random = random;
    	this.targetSuccesses = targetSuccesses;
    	this.tracker = tracker;
    	this.overallUIDTagCounter = overallUIDTagCounter;
    	generatedKeys = new HashSet<Key>();
    	requestHops = new SimpleSampleStatistics();
    	requestSuccess = new SimpleSampleStatistics();
        byte[] nonce = new byte[8];
        random.nextBytes(nonce);
        suffix = "-" + HexUtil.bytesToHex(nonce);
	}

    protected final Node[] nodes;
    protected final RandomSource random;
    private int requestNumber = 0;
    private RunningAverage requestsAvg = new SimpleRunningAverage(100, 0.0);
    protected final String baseString = "Test-";
    protected final String suffix;
	private int insertAttempts = 0;
	private int fetchSuccesses = 0;
	private final int targetSuccesses;
	protected final SimulatorRequestTracker tracker;
	private final TotalRequestUIDsCounter overallUIDTagCounter;
	
	/** Total number of inserts started so far. Requests started will trail this by PREINSERT_GAP */
	private int startedInserts=0;
	/** Number of requests running at present. Inserts are not included in this counter. */
	private int runningRequests = 0;
	/** Total number of requests completed so far. Should be equal to requestSuccess.countReports()
	 * modulo locking (i.e. eventually consistent). */
	private int completedRequests = 0;
	
	private final SimpleSampleStatistics requestHops;
	private final SimpleSampleStatistics requestSuccess;

	/** Run up to PARALLEL_REQUESTS requests simultaneously. Record the success rate and path 
	 * length, and overall rejection fraction. Initiate requests directly without using 
	 * client-side load limiting. Do not start recording data until we have executed 
	 * 4*PARALLEL_REQUESTS requests. Stop recording data when we run out of requests. 
	 * 
	 * FIXME How do we get rejection counts ignoring the prolog phase?
	 * @throws InvalidCompressionCodecException 
	 * @throws CHKEncodeException 
	 * @throws UnsupportedEncodingException 
	 * */
    protected int insertRequestTest() throws InterruptedException, UnsupportedEncodingException, CHKEncodeException, InvalidCompressionCodecException {
        if(startedInserts == TOTAL_REQUESTS) {
            // Terminate.
            dumpStats();
            return 0;
        } else {
            startedInserts++;
            waitForFreeRequestSlot();
            startInsert(startedInserts);
            if(startedInserts > PREINSERT_GAP) {
                int request = startedInserts - PREINSERT_GAP;
                Key key = waitForInsert(request);
                synchronized(this) {
                    runningRequests++;
                }
                startFetch(request, key, request > PROLOG_SIZE);
                if(request % 100 == 0)
                    dumpStats();
            }
            return -1;
        }
    }

    private void dumpStats() {
        System.err.println("Requests: "+startedInserts+" ("+requestSuccess.countReports()+")");
        System.err.println("Average request hops: "+requestHops.mean()+" +/- "+requestHops.stddev());
        System.err.println("Average request success: "+requestSuccess.mean()+" +/- "+requestSuccess.stddev());
    }
    
    protected void reportSuccess(int hops, boolean log) {
        if(log) {
            requestSuccess.report(1.0);
            requestHops.report(hops);
        }
        synchronized(this) {
            runningRequests--;
            completedRequests++;
            notifyAll();
        }
    }
    
    protected void reportFailure(boolean log) {
        if(log) {
            requestSuccess.report(0.0);
        }
        synchronized(this) {
            runningRequests--;
            completedRequests++;
            notifyAll();
        }
    }

    /** Start an asynchronous fetch.
     * @param req The key number.
     * @param key The key to fetch.
     * @param log True if statistics should be kept for this fetch (false in the prolog phase). */
    protected abstract void startFetch(int req, Key key, boolean log);

    class InsertWrapper {
        final int req;
        private boolean finished;
        private Key key;
        public InsertWrapper(int req) {
            this.req = req;
        }
        public void start() throws UnsupportedEncodingException, CHKEncodeException, InvalidCompressionCodecException {
            ClientKeyBlock block = generateBlock(req);
            startInsert(block, this);
        }
        public synchronized Key getKey() {
            return key;
        }
        public synchronized void waitForSuccess() throws InterruptedException {
            while(!finished) {
                wait();
            }
        }
        public synchronized void succeeded(Key key) {
            System.err.println("Finished insert "+req+" to "+key);
            Request[] reqs = tracker.dumpKey(key, true);
            if(reqs.length == 0)
                System.err.println("ERROR: Insert succeeded but no trace!");
            else {
                for(Request req : reqs)
                    System.err.print(req.dump(false, "Insert "+this.req+": "));
            }
            finished = true;
            this.key = key;
            notifyAll();
        }
    }
    
    private final HashMap<Integer, InsertWrapper> inserts = new HashMap<Integer, InsertWrapper>();
    
    private void startInsert(int req) throws UnsupportedEncodingException, CHKEncodeException, InvalidCompressionCodecException {
        System.err.println("Starting insert "+req);
        InsertWrapper insert = new InsertWrapper(req);
        synchronized(this) {
            inserts.put(req, insert);
        }
        insert.start();
    }

    /** Generate a ClientKeyBlock to insert for a specific request ID.
     * Can be overridden by subclasses for e.g. SSK vs CHK. 
     * @throws InvalidCompressionCodecException 
     * @throws CHKEncodeException 
     * @throws UnsupportedEncodingException */
    protected abstract ClientKeyBlock generateBlock(int req) throws UnsupportedEncodingException, CHKEncodeException, InvalidCompressionCodecException;
    
    protected ClientKeyBlock generateCHKBlock(int req) throws UnsupportedEncodingException, CHKEncodeException, InvalidCompressionCodecException {
        String dataString = baseString+req+suffix;
        byte[] buf = dataString.getBytes("UTF-8");
        return ClientCHKBlock.encode(buf, false, false, (short)-1, buf.length, COMPRESSOR_TYPE.DEFAULT_COMPRESSORDESCRIPTOR, false);
    }
    
    protected ClientKeyBlock generateSSKBlock(int req) throws SSKEncodeException, IOException, InvalidCompressionCodecException {
        String dataString = baseString+req+suffix;
        FreenetURI uri = new FreenetURI("KSK",dataString);
        InsertableClientSSK insertKey = InsertableClientSSK.create(uri);
        //ClientKSK fetchKey = ClientKSK.create(dataString);
        byte[] buf = dataString.getBytes("UTF-8");
        return 
            ((InsertableClientSSK)insertKey).encode(new ArrayBucket(buf), false, false, (short)-1, buf.length, random, COMPRESSOR_TYPE.DEFAULT_COMPRESSORDESCRIPTOR, false);
    }
    
    /** Start an insert asynchronously. Can be implemented in various ways. Must keep trying until 
     * it succeeds, or kill the simulation. */
    protected abstract void startInsert(ClientKeyBlock block, InsertWrapper insertWrapper);

    private Key waitForInsert(int req) throws InterruptedException {
        InsertWrapper wrapper;
        synchronized(this) {
            wrapper = inserts.get(req);
        }
        wrapper.waitForSuccess();
        return wrapper.getKey();
    }

    private synchronized void waitForFreeRequestSlot() throws InterruptedException {
        while(runningRequests >= PARALLEL_REQUESTS)
            wait();
    }
	
}
