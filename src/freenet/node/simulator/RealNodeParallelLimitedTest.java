package freenet.node.simulator;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;

import freenet.client.HighLevelSimpleClient;
import freenet.client.async.ClientBaseCallback;
import freenet.client.async.ClientContext;
import freenet.client.async.ClientGetState;
import freenet.client.async.ClientRequestScheduler;
import freenet.client.async.ClientRequester;
import freenet.client.async.LowLevelKeyFetcher;
import freenet.crypt.DummyRandomSource;
import freenet.crypt.RandomSource;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.keys.CHKEncodeException;
import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.FreenetURI;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.KeyDecodeException;
import freenet.keys.SSKEncodeException;
import freenet.node.BypassMessageQueue;
import freenet.node.BypassPacketFormat;
import freenet.node.FSParseException;
import freenet.node.LowLevelGetException;
import freenet.node.LowLevelPutException;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.NodeInitException;
import freenet.node.NodeStarter;
import freenet.node.SendableRequestItem;
import freenet.node.NodeStarter.TestNodeParameters;
import freenet.node.NodeStarter.TestingVMBypass;
import freenet.node.PeerNode;
import freenet.node.RequestClient;
import freenet.node.RequestCompletionListener;
import freenet.node.RequestScheduler;
import freenet.node.SimpleSendableInsert;
import freenet.node.simulator.LocalRequestUIDsCounter.NodeStatsSnapshot;
import freenet.node.simulator.SimulatorRequestTracker.Request;
import freenet.support.Executor;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.LoggerHook.InvalidThresholdException;
import freenet.support.PooledExecutor;
import freenet.support.PrioritizedTicker;
import freenet.support.compress.InvalidCompressionCodecException;
import freenet.support.io.FileUtil;

/** Lots of simultaneous request initiated by a single node, but using the client layer to test
 * client-side load limiting. */
public class RealNodeParallelLimitedTest extends RealNodeRequestInsertParallelTest implements RequestClient {

    private static volatile boolean logMINOR;
    private static volatile boolean logDEBUG;
    static {
        // This doesn't work if the class is loaded before the logger.
        Logger.registerClass(RealNodeParallelLimitedTest.class);
    }
    
    public static void main(String[] args) throws FSParseException, PeerParseException, CHKEncodeException, InvalidThresholdException, NodeInitException, ReferenceSignatureVerificationException, InterruptedException, SimulatorOverloadedException, SSKEncodeException, InvalidCompressionCodecException, IOException, KeyDecodeException {
        try {
        parseOptions(args);
        String name = "realNodeRequestInsertParallelTest";
        File wd = new File(name);
        if(!FileUtil.removeAll(wd)) {
            System.err.println("Mass delete failed, test may not be accurate.");
            System.exit(EXIT_CANNOT_DELETE_OLD_DATA);
        }
        wd.mkdir();
        //NOTE: globalTestInit returns in ignored random source
        //String logDetails = "freenet.node.Bypass:MINOR,freenet.node:MINOR";
        String logDetails = "";
        NodeStarter.globalTestInit(new File(name), false, LogLevel.ERROR, logDetails, true, 
                BYPASS_TRANSPORT_LAYER, null);
        // Need to call it explicitly because the class is loaded before we clobbered the logger.
        Logger.registerClass(RealNodeParallelLimitedTest.class);
        System.out.println("Parallel insert/retrieve test (single node originator)");
        System.out.println();
        System.err.println("Seed is "+SEED);
        System.err.println("Parallel requests: "+PARALLEL_REQUESTS);
        System.err.println("Bypass: "+BYPASS_TRANSPORT_LAYER);
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
        final LocalRequestUIDsCounter overallUIDTagCounter =
                new LocalRequestUIDsCounter();
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
        
        RealNodeRequestInsertParallelTest tester = 
            new RealNodeParallelLimitedTest(nodes, random, TARGET_SUCCESSES, tracker, overallUIDTagCounter);
        
        waitForAllConnected(nodes, true, true, false);
        while(true) {
            waitForAllConnected(nodes, true, false, true);
            int status = tester.insertRequestTest();
            if(status == -1) continue;
            System.exit(status);
        }
        } catch (Throwable t) {
            // Need to explicitly exit because the wrapper thread may prevent shutdown.
            // FIXME WTF? Shouldn't be using the wrapper???
            Logger.error(RealNodeRequestInsertParallelTest.class, "Caught "+t, t);
            System.err.println(t);
            t.printStackTrace();
            System.exit(1);
        }
    }

    public RealNodeParallelLimitedTest(Node[] nodes, DummyRandomSource random,
            int targetSuccesses, SimulatorRequestTracker tracker,
            LocalRequestUIDsCounter overallUIDTagCounter) {
        super(nodes, random, targetSuccesses, tracker, overallUIDTagCounter);
        spammer2 = nodes[0];
        keysFetching = new HashMap<Key,Integer>();
        keysInserting = new HashMap<Key,Integer>();
        fetchClientRequester = new MyRequester();
        fetcher = new MyKeyFetcher(spammer2.clientCore, spammer2.random, (short)0, false, false);
        fetchScheduler = spammer2.clientCore.clientContext.getChkFetchScheduler(false);
        fetchScheduler.getSelector().innerRegister(fetcher, spammer2.clientCore.clientContext, null);
    }

    private final Node spammer2;
    private final Map<Key,Integer> keysFetching;
    private final Map<Key,Integer> keysInserting;
    private final MyRequester fetchClientRequester;
    private final MyKeyFetcher fetcher;
    private final ClientRequestScheduler fetchScheduler;
    
    class MyRequester extends ClientRequester {

        @Override
        public void onTransition(ClientGetState oldState, ClientGetState newState,
                ClientContext context) {
            // Ignore
        }

        @Override
        public void cancel(ClientContext context) {
            // Ignore
        }

        @Override
        public FreenetURI getURI() {
            return null;
        }

        @Override
        public boolean isFinished() {
            return false;
        }

        @Override
        protected void innerNotifyClients(ClientContext context) {
            // Ignore
        }

        @Override
        protected void innerToNetwork(ClientContext context) {
            // Ignore
        }

        @Override
        protected ClientBaseCallback getCallback() {
            return null;
        }
        
    }
    
    class MyKeyFetcher extends LowLevelKeyFetcher {

        public MyKeyFetcher(NodeClientCore core, RandomSource random, short priorityClass,
                boolean isSSK, boolean realTimeFlag) {
            super(core, random, priorityClass, isSSK, realTimeFlag);
        }
        
        private final HashMap<Key,Long> startTimes = new HashMap<Key,Long>();
        
        @Override
        protected void requestSucceeded(Key key, RequestScheduler sched) {
            long startTime;
            synchronized(this) {
                startTime = startTimes.remove(key);
            }
            reportRequestSucceeded(key, sched, System.currentTimeMillis()-startTime);
        }

        @Override
        protected void requestFailed(Key key, RequestScheduler sched, LowLevelGetException e) {
            long startTime;
            synchronized(this) {
                startTime = startTimes.remove(key);
            }
            reportRequestFailed(key, sched, e, System.currentTimeMillis() - startTime);
        }

        @Override
        public ClientRequester getClientRequest() {
            return fetchClientRequester;
        }
        
        @Override
        public void queueKey(Key key) {
            synchronized(this) {
                startTimes.put(key, System.currentTimeMillis());
            }
            super.queueKey(key);
            super.clearWakeupTime(spammer2.clientCore.clientContext);
        }

        @Override
        protected boolean offersOnly() {
            return false;
        }
        
    }
    
    @Override
    protected void startFetch(int req, Key k, boolean log) {
        if(logMINOR) Logger.minor(this, "Fetching "+k+" for "+req);
        synchronized(this) {
            keysFetching.put(k, req);
        }
        fetcher.queueKey(k);
        fetchScheduler.wakeStarter();
    }

    public void reportRequestSucceeded(Key key, RequestScheduler sched, long timeTaken) {
        if(logMINOR) Logger.minor(this, "Request succeeded for "+key);
        int reqID;
        synchronized(this) {
            reqID = keysFetching.remove(key);
        }
        Request request = getRequest(key, false);
        if(request != null)
            Logger.normal(this, request.dump(false, "Request "+reqID+" : "));
        this.reportSuccess(getHops(request), shouldLog(reqID), timeTaken);
    }

    private Request getRequest(Key key, boolean insert) {
        Request[] dump = tracker.dumpKey(key, insert);
        if(dump.length == 0) return null;
        assert(dump.length == 1);
        return dump[0];
    }
    
    private int getHops(Request request) {
        if(request == null) return 0;
        else return request.count();
    }

    public void reportRequestFailed(Key key, RequestScheduler sched, LowLevelGetException e, long timeTaken) {
        if(logMINOR) Logger.minor(this, "Request failed for "+key+" : "+e);
        int reqID;
        synchronized(this) {
            reqID = keysFetching.remove(key);
        }
        Request request = getRequest(key, false);
        if(request != null)
            Logger.normal(this, request.dump(false, "Request "+reqID+" : "));
        this.reportFailure(shouldLog(reqID), timeTaken);
    }

    private Node randomNode() {
        return nodes[1+random.nextInt(nodes.length-1)];
    }

    @Override
    protected ClientKeyBlock generateBlock(int req) throws UnsupportedEncodingException, CHKEncodeException, InvalidCompressionCodecException {
        return generateCHKBlock(req);
    }

    @Override
    protected void startInsert(ClientKeyBlock block, InsertWrapper insertWrapper) {
        if(logMINOR) Logger.minor(this, "Inserting "+block.getKey()+" for "+insertWrapper.req);
        Node node = getInsertNode(insertWrapper.req);
        ClientRequestScheduler insertScheduler = node.clientCore.clientContext.getChkInsertScheduler(false);
        MyInsert insert = new MyInsert(block.getBlock(), (short)0, this, 
                node.clientCore.clientContext.getChkInsertScheduler(false), insertWrapper);
        insertScheduler.registerInsert(insert, false);
    }
    
    class MyInsert extends SimpleSendableInsert {
        
        final InsertWrapper wrapper;

        public MyInsert(KeyBlock block, short prioClass, RequestClient client,
                ClientRequestScheduler scheduler, InsertWrapper wrapper) {
            super(block, prioClass, client, scheduler);
            this.wrapper = wrapper;
        }
        
        @Override
        public void onSuccess(SendableRequestItem keyNum, ClientKey key, ClientContext context) {
            wrapper.succeeded(block.getKey());
        }
        
        @Override
        public void onFailure(LowLevelPutException e, SendableRequestItem keyNum, ClientContext context) {
            wrapper.failed(e.toString());
        }
        
    }

    @Override
    public boolean persistent() {
        return false;
    }

    @Override
    public boolean realTimeFlag() {
        return false;
    }
    
    protected synchronized void dumpStats() {
        super.dumpStats();
        NodeStatsSnapshot stats = this.overallUIDTagCounter.getStats(spammer2);
        System.err.println("Running requests overall "+overallUIDTagCounter.getCount());
        System.err.println("Running local requests on originator "+stats.runningLocalRequests+" average "+stats.averageRunningLocalRequests);
        System.err.println("Running requests on originator "+stats.runningRequests+" average "+stats.averageRunningRequests);
    }
    
}
