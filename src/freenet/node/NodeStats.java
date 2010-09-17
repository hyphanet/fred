package freenet.node;

import java.io.File;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.Map;

import freenet.config.InvalidConfigValueException;
import freenet.config.NodeNeedRestartException;
import freenet.config.SubConfig;
import freenet.crypt.RandomSource;
import freenet.io.comm.ByteCounter;
import freenet.io.comm.DMT;
import freenet.io.comm.Message;
import freenet.io.comm.MessageType;
import freenet.l10n.NodeL10n;
import freenet.node.Node.CountedRequests;
import freenet.node.NodeStats.ByteCountersSnapshot;
import freenet.node.NodeStats.PeerLoadStats;
import freenet.node.NodeStats.RunningRequestsSnapshot;
import freenet.node.SecurityLevels.NETWORK_THREAT_LEVEL;
import freenet.node.stats.NodeStoreStats;
import freenet.node.stats.StatsNotAvailableException;
import freenet.store.CHKStore;
import freenet.support.HTMLNode;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.SizeUtil;
import freenet.support.StringCounter;
import freenet.support.TimeUtil;
import freenet.support.TokenBucket;
import freenet.support.Logger.LogLevel;
import freenet.support.api.BooleanCallback;
import freenet.support.api.IntCallback;
import freenet.support.api.LongCallback;
import freenet.support.io.NativeThread;
import freenet.support.math.DecayingKeyspaceAverage;
import freenet.support.math.RunningAverage;
import freenet.support.math.TimeDecayingRunningAverage;
import freenet.support.math.TrivialRunningAverage;

/** Node (as opposed to NodeClientCore) level statistics. Includes shouldRejectRequest(), but not limited
 * to stuff required to implement that. */
public class NodeStats implements Persistable {

	public static enum RequestType {
		CHK_REQUEST,
		SSK_REQUEST,
		CHK_INSERT,
		SSK_INSERT,
		CHK_OFFER_FETCH,
		SSK_OFFER_FETCH;
	}
	
	/** Sub-max ping time. If ping is greater than this, we reject some requests. */
	public static final long DEFAULT_SUB_MAX_PING_TIME = 700;
	/** Maximum overall average ping time. If ping is greater than this,
	 * we reject all requests. */
	public static final long DEFAULT_MAX_PING_TIME = 1500;
	/** Maximum throttled packet delay. If the throttled packet delay is greater
	 * than this, reject all packets. */
	public static final long MAX_THROTTLE_DELAY = 3000;
	/** If the throttled packet delay is less than this, reject no packets; if it's
	 * between the two, reject some packets. */
	public static final long SUB_MAX_THROTTLE_DELAY = 2000;
	/** How high can bwlimitDelayTime be before we alert (in milliseconds)*/
	public static final long MAX_BWLIMIT_DELAY_TIME_ALERT_THRESHOLD = MAX_THROTTLE_DELAY*2;
	/** How high can nodeAveragePingTime be before we alert (in milliseconds)*/
	public static final long MAX_NODE_AVERAGE_PING_TIME_ALERT_THRESHOLD = DEFAULT_MAX_PING_TIME*2;
	/** How long we're over the bwlimitDelayTime threshold before we alert (in milliseconds)*/
	public static final long MAX_BWLIMIT_DELAY_TIME_ALERT_DELAY = 10*60*1000;  // 10 minutes
	/** How long we're over the nodeAveragePingTime threshold before we alert (in milliseconds)*/
	public static final long MAX_NODE_AVERAGE_PING_TIME_ALERT_DELAY = 10*60*1000;  // 10 minutes
	/** Accept one request every 10 seconds regardless, to ensure we update the
	 * block send time.
	 */
	public static final int MAX_INTERREQUEST_TIME = 10*1000;
	/** Locations of incoming requests */
	private final int[] incomingRequestsByLoc = new int[10];
	private int incomingRequestsAccounted = 0;
	/** Locations of outgoing requests */
	private final int[] outgoingLocalRequestByLoc = new int[10];
	private int outgoingLocalRequestsAccounted = 0;
	private final int[] outgoingRequestByLoc = new int[10];
	private int outgoingRequestsAccounted = 0;
	private volatile long subMaxPingTime;
	private volatile long maxPingTime;
    private final double nodeLoc=0.0;

	private final Node node;
	private MemoryChecker myMemoryChecker;
	public final PeerManager peers;

	final RandomSource hardRandom;

	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
				logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
			}
		});
	}

	/** first time bwlimitDelay was over PeerManagerUserAlert threshold */
	private long firstBwlimitDelayTimeThresholdBreak ;
	/** first time nodeAveragePing was over PeerManagerUserAlert threshold */
	private long firstNodeAveragePingTimeThresholdBreak;
	/** bwlimitDelay PeerManagerUserAlert should happen if true */
	public boolean bwlimitDelayAlertRelevant;
	/** nodeAveragePing PeerManagerUserAlert should happen if true */
	public boolean nodeAveragePingAlertRelevant;
	/** Average proportion of requests rejected immediately due to overload */
	public final TimeDecayingRunningAverage pInstantRejectIncoming;
	private boolean ignoreLocalVsRemoteBandwidthLiability;

	/** Average delay caused by throttling for sending a packet */
	final TimeDecayingRunningAverage throttledPacketSendAverage;

	// Bytes used by each different type of local/remote chk/ssk request/insert
	final TimeDecayingRunningAverage remoteChkFetchBytesSentAverage;
	final TimeDecayingRunningAverage remoteSskFetchBytesSentAverage;
	final TimeDecayingRunningAverage remoteChkInsertBytesSentAverage;
	final TimeDecayingRunningAverage remoteSskInsertBytesSentAverage;
	final TimeDecayingRunningAverage remoteChkFetchBytesReceivedAverage;
	final TimeDecayingRunningAverage remoteSskFetchBytesReceivedAverage;
	final TimeDecayingRunningAverage remoteChkInsertBytesReceivedAverage;
	final TimeDecayingRunningAverage remoteSskInsertBytesReceivedAverage;
	final TimeDecayingRunningAverage localChkFetchBytesSentAverage;
	final TimeDecayingRunningAverage localSskFetchBytesSentAverage;
	final TimeDecayingRunningAverage localChkInsertBytesSentAverage;
	final TimeDecayingRunningAverage localSskInsertBytesSentAverage;
	final TimeDecayingRunningAverage localChkFetchBytesReceivedAverage;
	final TimeDecayingRunningAverage localSskFetchBytesReceivedAverage;
	final TimeDecayingRunningAverage localChkInsertBytesReceivedAverage;
	final TimeDecayingRunningAverage localSskInsertBytesReceivedAverage;

	// Bytes used by successful chk/ssk request/insert.
	// Note: These are used to determine whether to accept a request,
	// hence they should be roughly representative of incoming - NOT LOCAL -
	// requests. Therefore, while we DO report local successful requests,
	// we only report the portion which will be consistent with a remote
	// request. If there is both a Handler and a Sender, it's a remote
	// request, report both. If there is only a Sender, report only the
	// received bytes (for a request). Etc.

	// Note that these are always reported in the Handler or the NodeClientCore
	// call taking its place.
	final TimeDecayingRunningAverage successfulChkFetchBytesSentAverage;
	final TimeDecayingRunningAverage successfulSskFetchBytesSentAverage;
	final TimeDecayingRunningAverage successfulChkInsertBytesSentAverage;
	final TimeDecayingRunningAverage successfulSskInsertBytesSentAverage;
	final TimeDecayingRunningAverage successfulChkOfferReplyBytesSentAverage;
	final TimeDecayingRunningAverage successfulSskOfferReplyBytesSentAverage;
	final TimeDecayingRunningAverage successfulChkFetchBytesReceivedAverage;
	final TimeDecayingRunningAverage successfulSskFetchBytesReceivedAverage;
	final TimeDecayingRunningAverage successfulChkInsertBytesReceivedAverage;
	final TimeDecayingRunningAverage successfulSskInsertBytesReceivedAverage;
	final TimeDecayingRunningAverage successfulChkOfferReplyBytesReceivedAverage;
	final TimeDecayingRunningAverage successfulSskOfferReplyBytesReceivedAverage;

	final TrivialRunningAverage globalFetchPSuccess;
	final TrivialRunningAverage chkLocalFetchPSuccess;
	final TrivialRunningAverage chkRemoteFetchPSuccess;
	final TrivialRunningAverage sskLocalFetchPSuccess;
	final TrivialRunningAverage sskRemoteFetchPSuccess;
	final TrivialRunningAverage blockTransferPSuccess;
	final TrivialRunningAverage blockTransferFailTurtled;
	final TrivialRunningAverage blockTransferFailTimeout;

	final TrivialRunningAverage successfulLocalCHKFetchTimeAverage;
	final TrivialRunningAverage unsuccessfulLocalCHKFetchTimeAverage;
	final TrivialRunningAverage localCHKFetchTimeAverage;

	private long previous_input_stat;
	private long previous_output_stat;
	private long previous_io_stat_time;
	private long last_input_stat;
	private long last_output_stat;
	private long last_io_stat_time;
	private final Object ioStatSync = new Object();
	/** Next time to update the node I/O stats */
	private long nextNodeIOStatsUpdateTime = -1;
	/** Node I/O stats update interval (milliseconds) */
	private static final long nodeIOStatsUpdateInterval = 2000;

	/** Token bucket for output bandwidth used by requests */
	final TokenBucket requestOutputThrottle;
	/** Token bucket for input bandwidth used by requests */
	final TokenBucket requestInputThrottle;

	// various metrics
	public final RunningAverage routingMissDistanceLocal;
	public final RunningAverage routingMissDistanceRemote;
	public final RunningAverage routingMissDistanceOverall;
	public final RunningAverage backedOffPercent;
	public final DecayingKeyspaceAverage avgCacheCHKLocation;
	public final DecayingKeyspaceAverage avgSlashdotCacheCHKLocation;
	//public final DecayingKeyspaceAverage avgSlashdotCacheLocation;
	public final DecayingKeyspaceAverage avgStoreCHKLocation;
	//public final DecayingKeyspaceAverage avgStoreLocation;
	public final DecayingKeyspaceAverage avgStoreCHKSuccess;

	// FIXME: does furthest{Store,Cache}Success need to be synchronized?
	public double furthestCacheCHKSuccess=0.0;
	public double furthestClientCacheCHKSuccess=0.0;
	public double furthestSlashdotCacheCHKSuccess=0.0;
	public double furthestStoreCHKSuccess=0.0;
	public double furthestStoreSSKSuccess=0.0;
	public double furthestCacheSSKSuccess=0.0;
	public double furthestClientCacheSSKSuccess=0.0;
	public double furthestSlashdotCacheSSKSuccess=0.0;
	protected final Persister persister;

	protected final DecayingKeyspaceAverage avgRequestLocation;

	// ThreadCounting stuffs
	public final ThreadGroup rootThreadGroup;
	private int[] activeThreadsByPriorities = new int[NativeThread.JAVA_PRIORITY_RANGE];
	private int[] waitingThreadsByPriorities = new int[NativeThread.JAVA_PRIORITY_RANGE];
	private int threadLimit;

	final NodePinger nodePinger;

	final StringCounter preemptiveRejectReasons;
	final StringCounter localPreemptiveRejectReasons;

	// Enable this if you run into hard to debug OOMs.
	// Disabled to prevent long pauses every 30 seconds.
	private int aggressiveGCModificator = -1 /*250*/;

	// Peers stats
	/** Next time to update PeerManagerUserAlert stats */
	private long nextPeerManagerUserAlertStatsUpdateTime = -1;
	/** PeerManagerUserAlert stats update interval (milliseconds) */
	private static final long peerManagerUserAlertStatsUpdateInterval = 1000;  // 1 second

	// Database stats
	final Hashtable<String, TrivialRunningAverage> avgDatabaseJobExecutionTimes;
	public final DecayingKeyspaceAverage avgClientCacheCHKLocation;
	public final DecayingKeyspaceAverage avgCacheCHKSuccess;
	public final DecayingKeyspaceAverage avgSlashdotCacheCHKSucess;
	public final DecayingKeyspaceAverage avgClientCacheCHKSuccess;

	public final DecayingKeyspaceAverage avgStoreSSKLocation;
	public final DecayingKeyspaceAverage avgCacheSSKLocation;
	public final DecayingKeyspaceAverage avgSlashdotCacheSSKLocation;
	public final DecayingKeyspaceAverage avgClientCacheSSKLocation;

	public final DecayingKeyspaceAverage avgCacheSSKSuccess;
	public final DecayingKeyspaceAverage avgSlashdotCacheSSKSuccess;
	public final DecayingKeyspaceAverage avgClientCacheSSKSuccess;
	public final DecayingKeyspaceAverage avgStoreSSKSuccess;


	NodeStats(Node node, int sortOrder, SubConfig statsConfig, int obwLimit, int ibwLimit, int lastVersion) throws NodeInitException {
		this.node = node;
		this.peers = node.peers;
		this.hardRandom = node.random;
		this.routingMissDistanceLocal = new TimeDecayingRunningAverage(0.0, 180000, 0.0, 1.0, node);
		this.routingMissDistanceRemote = new TimeDecayingRunningAverage(0.0, 180000, 0.0, 1.0, node);
		this.routingMissDistanceOverall = new TimeDecayingRunningAverage(0.0, 180000, 0.0, 1.0, node);
		this.backedOffPercent = new TimeDecayingRunningAverage(0.0, 180000, 0.0, 1.0, node);
		preemptiveRejectReasons = new StringCounter();
		localPreemptiveRejectReasons = new StringCounter();
		pInstantRejectIncoming = new TimeDecayingRunningAverage(0, 60000, 0.0, 1.0, node);
		ThreadGroup tg = Thread.currentThread().getThreadGroup();
		while(tg.getParent() != null) tg = tg.getParent();
		this.rootThreadGroup = tg;
		this.activeThreadsByPriorities = new int[NativeThread.JAVA_PRIORITY_RANGE];
		this.waitingThreadsByPriorities = new int[NativeThread.JAVA_PRIORITY_RANGE];
		throttledPacketSendAverage =
			new TimeDecayingRunningAverage(1, 10*60*1000 /* should be significantly longer than a typical transfer */, 0, Long.MAX_VALUE, node);
		nodePinger = new NodePinger(node);

		previous_input_stat = 0;
		previous_output_stat = 0;
		previous_io_stat_time = 1;
		last_input_stat = 0;
		last_output_stat = 0;
		last_io_stat_time = 3;

		int defaultThreadLimit;
		long memoryLimit = Runtime.getRuntime().maxMemory();
		
		System.out.println("Memory is "+SizeUtil.formatSize(memoryLimit)+" ("+memoryLimit+" bytes)");
		if(memoryLimit > 0 && memoryLimit < 100*1024*1024) {
			defaultThreadLimit = 200;
			System.out.println("Severe memory pressure, setting 200 thread limit. Freenet may not work well!");
		} else if(memoryLimit > 0 && memoryLimit < 160*1024*1024) {
			defaultThreadLimit = 300;
			System.out.println("Moderate memory pressure, setting 300 thread limit. Increase your memory limit in wrapper.conf if possible.");
		// FIXME: reinstate this once either we raise the default or memory autodetection works on Windows.
//		else if(memoryLimit > 0 && memoryLimit < 256*1024*1024)
//			defaultThreadLimit = 400;
		} else {
			System.out.println("Setting standard 500 thread limit. This should be enough for most nodes but more memory is usually a good thing.");
			defaultThreadLimit = 500;
		}
		statsConfig.register("threadLimit", defaultThreadLimit, sortOrder++, true, true, "NodeStat.threadLimit", "NodeStat.threadLimitLong",
				new IntCallback() {
					@Override
					public Integer get() {
						return threadLimit;
					}
					@Override
					public void set(Integer val) throws InvalidConfigValueException {
						if (get().equals(val))
					        return;
						if(val < 100)
							throw new InvalidConfigValueException(l10n("valueTooLow"));
						threadLimit = val;
					}
		},false);
		
		if(lastVersion > 0 && lastVersion < 1270 && memoryLimit > 160*1024*1024 && memoryLimit < 192*1024*1024)
			statsConfig.fixOldDefault("threadLimit", "300");
		
		threadLimit = statsConfig.getInt("threadLimit");

		// Yes it could be in seconds insteed of multiples of 0.12, but we don't want people to play with it :)
		statsConfig.register("aggressiveGC", aggressiveGCModificator, sortOrder++, true, false, "NodeStat.aggressiveGC", "NodeStat.aggressiveGCLong",
				new IntCallback() {
					@Override
					public Integer get() {
						return aggressiveGCModificator;
					}
					@Override
					public void set(Integer val) throws InvalidConfigValueException {
						if (get().equals(val))
					        return;
						Logger.normal(this, "Changing aggressiveGCModificator to "+val);
						aggressiveGCModificator = val;
					}
		},false);
		aggressiveGCModificator = statsConfig.getInt("aggressiveGC");

		myMemoryChecker = new MemoryChecker(node.ps, aggressiveGCModificator);
		statsConfig.register("memoryChecker", true, sortOrder++, true, false, "NodeStat.memCheck", "NodeStat.memCheckLong",
				new BooleanCallback(){
					@Override
					public Boolean get() {
						return myMemoryChecker.isRunning();
					}

					@Override
					public void set(Boolean val) throws InvalidConfigValueException {
						if (get().equals(val))
					        return;

						if(val)
							myMemoryChecker.start();
						else
							myMemoryChecker.terminate();
					}
		});
		if(statsConfig.getBoolean("memoryChecker"))
			myMemoryChecker.start();

		statsConfig.register("ignoreLocalVsRemoteBandwidthLiability", false, sortOrder++, true, false, "NodeStat.ignoreLocalVsRemoteBandwidthLiability", "NodeStat.ignoreLocalVsRemoteBandwidthLiabilityLong", new BooleanCallback() {

			@Override
			public Boolean get() {
				synchronized(NodeStats.this) {
					return ignoreLocalVsRemoteBandwidthLiability;
				}
			}

			@Override
			public void set(Boolean val) throws InvalidConfigValueException {
				synchronized(NodeStats.this) {
					ignoreLocalVsRemoteBandwidthLiability = val;
				}
			}
		});

		statsConfig.register("maxPingTime", DEFAULT_MAX_PING_TIME, sortOrder++, true, true, "NodeStat.maxPingTime", "NodeStat.maxPingTimeLong", new LongCallback() {

			@Override
			public Long get() {
				return maxPingTime;
			}

			@Override
			public void set(Long val) throws InvalidConfigValueException, NodeNeedRestartException {
				maxPingTime = val;
			}

		}, false);
		maxPingTime = statsConfig.getLong("maxPingTime");

		statsConfig.register("subMaxPingTime", DEFAULT_SUB_MAX_PING_TIME, sortOrder++, true, true, "NodeStat.subMaxPingTime", "NodeStat.subMaxPingTimeLong", new LongCallback() {

			@Override
			public Long get() {
				return subMaxPingTime;
			}

			@Override
			public void set(Long val) throws InvalidConfigValueException, NodeNeedRestartException {
				subMaxPingTime = val;
			}

		}, false);
		subMaxPingTime = statsConfig.getLong("subMaxPingTime");

		// This is a *network* level setting, because it affects the rate at which we initiate local
		// requests, which could be seen by distant nodes.

		node.securityLevels.addNetworkThreatLevelListener(new SecurityLevelListener<NETWORK_THREAT_LEVEL>() {

			public void onChange(NETWORK_THREAT_LEVEL oldLevel, NETWORK_THREAT_LEVEL newLevel) {
				if(newLevel == NETWORK_THREAT_LEVEL.MAXIMUM)
					ignoreLocalVsRemoteBandwidthLiability = true;
				if(oldLevel == NETWORK_THREAT_LEVEL.MAXIMUM)
					ignoreLocalVsRemoteBandwidthLiability = false;
				// Otherwise leave it as it was. It defaults to false.
			}

		});

		persister = new ConfigurablePersister(this, statsConfig, "nodeThrottleFile", "node-throttle.dat", sortOrder++, true, false,
				"NodeStat.statsPersister", "NodeStat.statsPersisterLong", node.ps, node.getRunDir());

		SimpleFieldSet throttleFS = persister.read();
		if(logMINOR) Logger.minor(this, "Read throttleFS:\n"+throttleFS);

		// Guesstimates. Hopefully well over the reality.
		localChkFetchBytesSentAverage = new TimeDecayingRunningAverage(500, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("LocalChkFetchBytesSentAverage"), node);
		localSskFetchBytesSentAverage = new TimeDecayingRunningAverage(500, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("LocalSskFetchBytesSentAverage"), node);
		localChkInsertBytesSentAverage = new TimeDecayingRunningAverage(32768, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("LocalChkInsertBytesSentAverage"), node);
		localSskInsertBytesSentAverage = new TimeDecayingRunningAverage(2048, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("LocalSskInsertBytesSentAverage"), node);
		localChkFetchBytesReceivedAverage = new TimeDecayingRunningAverage(32768+2048/*path folding*/, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("LocalChkFetchBytesReceivedAverage"), node);
		localSskFetchBytesReceivedAverage = new TimeDecayingRunningAverage(2048, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("LocalSskFetchBytesReceivedAverage"), node);
		localChkInsertBytesReceivedAverage = new TimeDecayingRunningAverage(1024, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("LocalChkInsertBytesReceivedAverage"), node);
		localSskInsertBytesReceivedAverage = new TimeDecayingRunningAverage(500, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("LocalChkInsertBytesReceivedAverage"), node);

		remoteChkFetchBytesSentAverage = new TimeDecayingRunningAverage(32768+1024+500+2048/*path folding*/, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("RemoteChkFetchBytesSentAverage"), node);
		remoteSskFetchBytesSentAverage = new TimeDecayingRunningAverage(1024+1024+500, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("RemoteSskFetchBytesSentAverage"), node);
		remoteChkInsertBytesSentAverage = new TimeDecayingRunningAverage(32768+32768+1024, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("RemoteChkInsertBytesSentAverage"), node);
		remoteSskInsertBytesSentAverage = new TimeDecayingRunningAverage(1024+1024+500, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("RemoteSskInsertBytesSentAverage"), node);
		remoteChkFetchBytesReceivedAverage = new TimeDecayingRunningAverage(32768+1024+500+2048/*path folding*/, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("RemoteChkFetchBytesReceivedAverage"), node);
		remoteSskFetchBytesReceivedAverage = new TimeDecayingRunningAverage(2048+500, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("RemoteSskFetchBytesReceivedAverage"), node);
		remoteChkInsertBytesReceivedAverage = new TimeDecayingRunningAverage(32768+1024+500, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("RemoteChkInsertBytesReceivedAverage"), node);
		remoteSskInsertBytesReceivedAverage = new TimeDecayingRunningAverage(1024+1024+500, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("RemoteSskInsertBytesReceivedAverage"), node);

		successfulChkFetchBytesSentAverage = new TimeDecayingRunningAverage(32768+1024+500+2048/*path folding*/, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("SuccessfulChkFetchBytesSentAverage"), node);
		successfulSskFetchBytesSentAverage = new TimeDecayingRunningAverage(1024+1024+500, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("SuccessfulSskFetchBytesSentAverage"), node);
		successfulChkInsertBytesSentAverage = new TimeDecayingRunningAverage(32768+32768+1024, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("SuccessfulChkInsertBytesSentAverage"), node);
		successfulSskInsertBytesSentAverage = new TimeDecayingRunningAverage(1024+1024+500, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("SuccessfulSskInsertBytesSentAverage"), node);
		successfulChkOfferReplyBytesSentAverage = new TimeDecayingRunningAverage(32768+500, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("successfulChkOfferReplyBytesSentAverage"), node);
		successfulSskOfferReplyBytesSentAverage = new TimeDecayingRunningAverage(3072, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("successfulSskOfferReplyBytesSentAverage"), node);
		successfulChkFetchBytesReceivedAverage = new TimeDecayingRunningAverage(32768+1024+500+2048/*path folding*/, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("SuccessfulChkFetchBytesReceivedAverage"), node);
		successfulSskFetchBytesReceivedAverage = new TimeDecayingRunningAverage(2048+500, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("SuccessfulSskFetchBytesReceivedAverage"), node);
		successfulChkInsertBytesReceivedAverage = new TimeDecayingRunningAverage(32768+1024+500, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("SuccessfulChkInsertBytesReceivedAverage"), node);
		successfulSskInsertBytesReceivedAverage = new TimeDecayingRunningAverage(1024+1024+500, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("SuccessfulSskInsertBytesReceivedAverage"), node);
		successfulChkOfferReplyBytesReceivedAverage = new TimeDecayingRunningAverage(32768+500, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("successfulChkOfferReplyBytesReceivedAverage"), node);
		successfulSskOfferReplyBytesReceivedAverage = new TimeDecayingRunningAverage(3072, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("successfulSskOfferReplyBytesReceivedAverage"), node);

		globalFetchPSuccess = new TrivialRunningAverage();
		chkLocalFetchPSuccess = new TrivialRunningAverage();
		chkRemoteFetchPSuccess = new TrivialRunningAverage();
		sskLocalFetchPSuccess = new TrivialRunningAverage();
		sskRemoteFetchPSuccess = new TrivialRunningAverage();
		blockTransferPSuccess = new TrivialRunningAverage();
		blockTransferFailTurtled = new TrivialRunningAverage();
		blockTransferFailTimeout = new TrivialRunningAverage();

		successfulLocalCHKFetchTimeAverage = new TrivialRunningAverage();
		unsuccessfulLocalCHKFetchTimeAverage = new TrivialRunningAverage();
		localCHKFetchTimeAverage = new TrivialRunningAverage();

		requestOutputThrottle =
			new TokenBucket(Math.max(obwLimit*60, 32768*20), (int)((1000L*1000L*1000L) / (obwLimit)), 0);
		requestInputThrottle =
			new TokenBucket(Math.max(ibwLimit*60, 32768*20), (int)((1000L*1000L*1000L) / (ibwLimit)), 0);

		estimatedSizeOfOneThrottledPacket = 1024 + DMT.packetTransmitSize(1024, 32) +
			node.estimateFullHeadersLengthOneMessage();

		double nodeLoc=node.lm.getLocation();
		// FIXME PLEASE; (int) casts; (maxCacheKeys>MAXINT?)
		//Note: If changing the size of avgCacheLocation or avgStoreLocation, this value is updated in Node.java on changing the store size.
		this.avgCacheCHKLocation   = new DecayingKeyspaceAverage(nodeLoc, (int)node.maxCacheKeys, throttleFS == null ? null : throttleFS.subset("AverageCacheCHKLocation"));
		this.avgStoreCHKLocation   = new DecayingKeyspaceAverage(nodeLoc, (int)node.maxStoreKeys, throttleFS == null ? null : throttleFS.subset("AverageStoreCHKLocation"));
		this.avgSlashdotCacheCHKLocation = new DecayingKeyspaceAverage(nodeLoc, (int)node.maxCacheKeys, throttleFS == null ? null : throttleFS.subset("AverageSlashdotCacheCHKLocation"));
		this.avgClientCacheCHKLocation = new DecayingKeyspaceAverage(nodeLoc, (int)node.maxCacheKeys, throttleFS == null ? null : throttleFS.subset("AverageClientCacheCHKLocation"));

		this.avgCacheCHKSuccess    = new DecayingKeyspaceAverage(nodeLoc, 10000, throttleFS == null ? null : throttleFS.subset("AverageCacheCHKSuccessLocation"));
		this.avgSlashdotCacheCHKSucess =  new DecayingKeyspaceAverage(nodeLoc, 10000, throttleFS == null ? null : throttleFS.subset("AverageSlashdotCacheCHKSuccessLocation"));
		this.avgClientCacheCHKSuccess    = new DecayingKeyspaceAverage(nodeLoc, 10000, throttleFS == null ? null : throttleFS.subset("AverageClientCacheCHKSuccessLocation"));
		this.avgStoreCHKSuccess    = new DecayingKeyspaceAverage(nodeLoc, 10000, throttleFS == null ? null : throttleFS.subset("AverageStoreCHKSuccessLocation"));
		this.avgRequestLocation = new DecayingKeyspaceAverage(nodeLoc, 10000, throttleFS == null ? null : throttleFS.subset("AverageRequestLocation"));

		this.avgCacheSSKLocation   = new DecayingKeyspaceAverage(nodeLoc, (int)node.maxCacheKeys, throttleFS == null ? null : throttleFS.subset("AverageCacheSSKLocation"));
		this.avgStoreSSKLocation   = new DecayingKeyspaceAverage(nodeLoc, (int)node.maxStoreKeys, throttleFS == null ? null : throttleFS.subset("AverageStoreSSKLocation"));
		this.avgSlashdotCacheSSKLocation = new DecayingKeyspaceAverage(nodeLoc, (int)node.maxCacheKeys, throttleFS == null ? null : throttleFS.subset("AverageSlashdotCacheSSKLocation"));
		this.avgClientCacheSSKLocation = new DecayingKeyspaceAverage(nodeLoc, (int)node.maxCacheKeys, throttleFS == null ? null : throttleFS.subset("AverageClientCacheSSKLocation"));

		this.avgCacheSSKSuccess    = new DecayingKeyspaceAverage(nodeLoc, 10000, throttleFS == null ? null : throttleFS.subset("AverageCacheSSKSuccessLocation"));
		this.avgSlashdotCacheSSKSuccess =  new DecayingKeyspaceAverage(nodeLoc, 10000, throttleFS == null ? null : throttleFS.subset("AverageSlashdotCacheSSKSuccessLocation"));
		this.avgClientCacheSSKSuccess    = new DecayingKeyspaceAverage(nodeLoc, 10000, throttleFS == null ? null : throttleFS.subset("AverageClientCacheSSKSuccessLocation"));
		this.avgStoreSSKSuccess    = new DecayingKeyspaceAverage(nodeLoc, 10000, throttleFS == null ? null : throttleFS.subset("AverageStoreSSKSuccessLocation"));




		hourlyStats = new HourlyStats(node);

		avgDatabaseJobExecutionTimes = new Hashtable<String, TrivialRunningAverage>();
	}

	protected String l10n(String key) {
		return NodeL10n.getBase().getString("NodeStats."+key);
	}

	public void start() throws NodeInitException {
		node.executor.execute(new Runnable() {
			public void run() {
				nodePinger.start();
			}
		}, "Starting NodePinger");
		persister.start();
		node.getTicker().queueTimedJob(throttledPacketSendAverageIdleUpdater, CHECK_THROTTLE_TIME);
	}

	/** Every 60 seconds, check whether we need to adjust the bandwidth delay time because of idleness.
	 * (If no packets have been sent, the throttledPacketSendAverage should decrease; if it doesn't, it may go high,
	 * and then no requests will be accepted, and it will stay high forever. */
	static final int CHECK_THROTTLE_TIME = 60 * 1000;
	/** Absolute limit of 4MB queued to any given peer. FIXME make this configurable.
	 * Note that for many MessageItem's, the actual memory usage will be significantly more than this figure. */
	private static final long MAX_PEER_QUEUE_BYTES = 4 * 1024 * 1024;
	/** Don't accept requests if it'll take more than 1 minutes to send the current message queue.
	 * On the assumption that most of the message queue is block transfer data.
	 * Note that this only applies to data on the queue before calling shouldRejectRequest(): we
	 * do *not* attempt to include any estimate of how much the request will add to it. This is
	 * important because if we did, the AIMD may not have reached sufficient speed to transfer it
	 * in 60 seconds yet, because it hasn't had enough data in transit to need to increase its speed.
	 * 
	 * Interaction with output bandwidth liability: This must be slightly larger than the
	 * output bandwidth liability time limit (combined for both types).
	 * 
	 * A fast peer can have slightly more than half our output limit queued in requests to run. 
	 * If they all complete, they will take half the time limit. If they are all served from the 
	 * store, this will be shown on the queue time. But the queue time is estimated based on 
	 * using at most half the limit, so the time will be slightly over the overall limit. */
	private static final double MAX_PEER_QUEUE_TIME = 4 * 60 * 1000.0;

	private long lastAcceptedRequest = -1;

	final int estimatedSizeOfOneThrottledPacket;

	final Runnable throttledPacketSendAverageIdleUpdater =
		new Runnable() {
			public void run() {
				long now = System.currentTimeMillis();
				try {
					if(throttledPacketSendAverage.lastReportTime() < now - 5000) {  // if last report more than 5 seconds ago
						// shouldn't take long
						node.outputThrottle.blockingGrab(estimatedSizeOfOneThrottledPacket);
						node.outputThrottle.recycle(estimatedSizeOfOneThrottledPacket);
						long after = System.currentTimeMillis();
						// Report time it takes to grab the bytes.
						throttledPacketSendAverage.report(after - now);
					}
				} catch (Throwable t) {
					Logger.error(this, "Caught "+t, t);
				} finally {
					node.getTicker().queueTimedJob(this, CHECK_THROTTLE_TIME);
					long end = System.currentTimeMillis();
					if(logMINOR)
						Logger.minor(this, "Throttle check took "+TimeUtil.formatTime(end-now,2,true));

					// Doesn't belong here... but anyway, should do the job.
					activeThreadsByPriorities = node.executor.runningThreads();
					waitingThreadsByPriorities = node.executor.waitingThreads();
				}
			}
	};

	static final double DEFAULT_OVERHEAD = 0.7;
	static final long DEFAULT_ONLY_PERIOD = 60*1000;
	static final long DEFAULT_TRANSITION_PERIOD = 240*1000;
	/** Relatively high minimum overhead. A low overhead estimate becomes a self-fulfilling
	 * prophecy, and it takes a long time to shake it off as the averages gradually increase.
	 * If we accept no requests then everything is overhead! Whereas with a high minimum
	 * overhead the worst case is that more stuff succeeds than expected and we have a few
	 * timeouts (because output bandwidth liability was assuming a lower overhead than
	 * actually happens) - but this should be very rare. */
	static final double MIN_NON_OVERHEAD = 0.5;
	
	/** All requests must be able to complete in this many seconds given the bandwidth
	 * available, even if they all succeed. Bulk requests. */
	static final int BANDWIDTH_LIABILITY_LIMIT_SECONDS_BULK = 180;
	/** All requests must be able to complete in this many seconds given the bandwidth
	 * available, even if they all succeed. Realtime requests - separate from bulk 
	 * requests, given higher priority but expected to be bursty and lower capacity. */
	static final int BANDWIDTH_LIABILITY_LIMIT_SECONDS_REALTIME = 60;
	
	/** Stats to send to a single peer so it can determine whether we are likely to reject 
	 * a request. */
	public class PeerLoadStats {
		
		public final PeerNode peer;
		/** These do not include those from the peer */
		public final int expectedTransfersOutCHK;
		public final int expectedTransfersInCHK;
		public final int expectedTransfersOutSSK;
		public final int expectedTransfersInSSK;
		public final double outputBandwidthLowerLimit;
		public final double outputBandwidthUpperLimit;
		public final double outputBandwidthPeerLimit;
		public final double inputBandwidthLowerLimit;
		public final double inputBandwidthUpperLimit;
		public final double inputBandwidthPeerLimit;
		public final boolean realTime;
		public final int totalRequests;
		
		public boolean equals(Object o) {
			if(!(o instanceof PeerLoadStats)) return false;
			PeerLoadStats s = (PeerLoadStats)o;
			if(s.peer != peer) return false;
			if(s.expectedTransfersOutCHK != expectedTransfersOutCHK) return false;
			if(s.expectedTransfersInCHK != expectedTransfersInCHK) return false;
			if(s.expectedTransfersOutSSK != expectedTransfersOutSSK) return false;
			if(s.expectedTransfersInSSK != expectedTransfersInSSK) return false;
			if(s.totalRequests != totalRequests) return false;
			if(s.outputBandwidthLowerLimit != outputBandwidthLowerLimit) return false;
			if(s.outputBandwidthUpperLimit != outputBandwidthUpperLimit) return false;
			if(s.outputBandwidthPeerLimit != outputBandwidthPeerLimit) return false;
			if(s.inputBandwidthLowerLimit != inputBandwidthLowerLimit) return false;
			if(s.inputBandwidthUpperLimit != inputBandwidthUpperLimit) return false;
			if(s.inputBandwidthPeerLimit != inputBandwidthPeerLimit) return false;
			return true;
		}
		
		public String toString() {
			return peer.toString()+":output:{lower="+outputBandwidthLowerLimit+",upper="+outputBandwidthUpperLimit+",this="+outputBandwidthPeerLimit+"},input:lower="+inputBandwidthLowerLimit+",upper="+inputBandwidthUpperLimit+",peer="+inputBandwidthPeerLimit+"},requests:"+
				"in:"+expectedTransfersInCHK+"chk/"+expectedTransfersInSSK+"ssk:out:"+
				expectedTransfersOutCHK+"chk/"+expectedTransfersOutSSK+"ssk"+":rt="+realTime;
		}
		
		public PeerLoadStats(PeerNode peer, boolean realTimeFlag) {
			this.peer = peer;
			this.realTime = realTimeFlag;
			long[] total = node.collector.getTotalIO();
			long totalSent = total[0];
			long totalOverhead = getSentOverhead();
			long uptime = node.getUptime();
			
			long now = System.currentTimeMillis();
			
			double nonOverheadFraction = getNonOverheadFraction(totalSent, totalOverhead, uptime, now);
			
			int limit = realTimeFlag ? BANDWIDTH_LIABILITY_LIMIT_SECONDS_REALTIME : BANDWIDTH_LIABILITY_LIMIT_SECONDS_BULK;
			
			outputBandwidthUpperLimit = getOutputBandwidthUpperLimit(totalSent, totalOverhead, uptime, limit, nonOverheadFraction);
			outputBandwidthLowerLimit = outputBandwidthUpperLimit / 2;
			
			inputBandwidthUpperLimit = getInputBandwidthUpperLimit(limit);
			inputBandwidthLowerLimit = inputBandwidthUpperLimit / 2;
			
			outputBandwidthPeerLimit = getPeerLimit(peer, outputBandwidthLowerLimit, false, realTimeFlag, true);
			inputBandwidthPeerLimit = getPeerLimit(peer, inputBandwidthLowerLimit, true, realTimeFlag, true);
			
			boolean ignoreLocalVsRemote = ignoreLocalVsRemoteBandwidthLiability();
			int transfersPerInsert = outwardTransfersPerInsert();
			
			RunningRequestsSnapshot runningGlobal = new RunningRequestsSnapshot(node, realTimeFlag, ignoreLocalVsRemote, transfersPerInsert);
			RunningRequestsSnapshot runningLocal = new RunningRequestsSnapshot(node, peer, false, realTimeFlag, ignoreLocalVsRemote, transfersPerInsert);
			expectedTransfersInCHK = runningGlobal.expectedTransfersInCHK - runningLocal.expectedTransfersInCHK;
			expectedTransfersInSSK = runningGlobal.expectedTransfersInSSK - runningLocal.expectedTransfersInSSK;
			expectedTransfersOutCHK = runningGlobal.expectedTransfersOutCHK - runningLocal.expectedTransfersOutCHK;
			expectedTransfersOutSSK = runningGlobal.expectedTransfersOutSSK - runningLocal.expectedTransfersOutSSK;
			totalRequests = runningGlobal.totalRequests - runningLocal.totalRequests;
		}

		public PeerLoadStats(PeerNode source, Message m) {
			peer = source;
			if(m.getSpec() == DMT.FNPPeerLoadStatusInt) {
				expectedTransfersInCHK = m.getInt(DMT.OTHER_TRANSFERS_IN_CHK);
				expectedTransfersInSSK = m.getInt(DMT.OTHER_TRANSFERS_IN_SSK);
				expectedTransfersOutCHK = m.getInt(DMT.OTHER_TRANSFERS_OUT_CHK);
				expectedTransfersOutSSK = m.getInt(DMT.OTHER_TRANSFERS_OUT_SSK);
			} else if(m.getSpec() == DMT.FNPPeerLoadStatusShort) {
				expectedTransfersInCHK = m.getShort(DMT.OTHER_TRANSFERS_IN_CHK) & 0xFFFF;
				expectedTransfersInSSK = m.getShort(DMT.OTHER_TRANSFERS_IN_SSK) & 0xFFFF;
				expectedTransfersOutCHK = m.getShort(DMT.OTHER_TRANSFERS_OUT_CHK) & 0xFFFF;
				expectedTransfersOutSSK = m.getShort(DMT.OTHER_TRANSFERS_OUT_SSK) & 0xFFFF;
			} else if(m.getSpec() == DMT.FNPPeerLoadStatusByte) {
				expectedTransfersInCHK = m.getByte(DMT.OTHER_TRANSFERS_IN_CHK) & 0xFF;
				expectedTransfersInSSK = m.getByte(DMT.OTHER_TRANSFERS_IN_SSK) & 0xFF;
				expectedTransfersOutCHK = m.getByte(DMT.OTHER_TRANSFERS_OUT_CHK) & 0xFF;
				expectedTransfersOutSSK = m.getByte(DMT.OTHER_TRANSFERS_OUT_SSK) & 0xFF;
			} else throw new IllegalArgumentException();
			outputBandwidthLowerLimit = m.getInt(DMT.OUTPUT_BANDWIDTH_LOWER_LIMIT);
			outputBandwidthUpperLimit = m.getInt(DMT.OUTPUT_BANDWIDTH_UPPER_LIMIT);
			outputBandwidthPeerLimit = m.getInt(DMT.OUTPUT_BANDWIDTH_PEER_LIMIT);
			inputBandwidthLowerLimit = m.getInt(DMT.INPUT_BANDWIDTH_LOWER_LIMIT);
			inputBandwidthUpperLimit = m.getInt(DMT.INPUT_BANDWIDTH_UPPER_LIMIT);
			inputBandwidthPeerLimit = m.getInt(DMT.INPUT_BANDWIDTH_PEER_LIMIT);
			totalRequests = -1;
			realTime = m.getBoolean(DMT.REAL_TIME_FLAG);
		}

		public RunningRequestsSnapshot getOtherRunningRequests() {
			return new RunningRequestsSnapshot(this);
		}

		public double peerLimit(boolean input) {
			if(input)
				return inputBandwidthPeerLimit;
			else
				return outputBandwidthPeerLimit;
		}

		public double lowerLimit(boolean input) {
			if(input)
				return inputBandwidthLowerLimit;
			else
				return outputBandwidthLowerLimit;
		}
		
	}
	
	/** This is per direction. */
	class ByteCountersSnapshot {
		
		public ByteCountersSnapshot(boolean input) {
			this.input = input;
			if(input) {
				successfulChkFetchBytes = successfulChkFetchBytesSentAverage.currentValue();
				successfulSskFetchBytes = successfulSskFetchBytesSentAverage.currentValue();
				successfulChkInsertBytes = successfulChkInsertBytesSentAverage.currentValue();
				successfulSskInsertBytes = successfulSskInsertBytesSentAverage.currentValue();
				localChkFetchBytes = localChkFetchBytesSentAverage.currentValue();
				localSskFetchBytes = localSskFetchBytesSentAverage.currentValue();
				localChkInsertBytes = localChkInsertBytesSentAverage.currentValue();
				localSskInsertBytes = localSskInsertBytesSentAverage.currentValue();
				successfulChkOfferReplyBytes = successfulChkOfferReplyBytesSentAverage.currentValue();
				successfulSskOfferReplyBytes = successfulSskOfferReplyBytesSentAverage.currentValue();
			} else {
				successfulChkFetchBytes = successfulChkFetchBytesReceivedAverage.currentValue();
				successfulSskFetchBytes = successfulSskFetchBytesReceivedAverage.currentValue();
				successfulChkInsertBytes = successfulChkInsertBytesReceivedAverage.currentValue();
				successfulSskInsertBytes = successfulSskInsertBytesReceivedAverage.currentValue();
				localChkFetchBytes = localChkFetchBytesReceivedAverage.currentValue();
				localSskFetchBytes = localSskFetchBytesReceivedAverage.currentValue();
				localChkInsertBytes = localChkInsertBytesReceivedAverage.currentValue();
				localSskInsertBytes = localSskInsertBytesReceivedAverage.currentValue();
				successfulChkOfferReplyBytes = successfulChkOfferReplyBytesReceivedAverage.currentValue();
				successfulSskOfferReplyBytes = successfulSskOfferReplyBytesReceivedAverage.currentValue();
			}
		}
		final boolean input;
		final double successfulChkFetchBytes;
		final double successfulSskFetchBytes;
		final double successfulChkInsertBytes;
		final double successfulSskInsertBytes;
		final double localChkFetchBytes;
		final double localSskFetchBytes;
		final double localChkInsertBytes;
		final double localSskInsertBytes;
		final double successfulChkOfferReplyBytes;
		final double successfulSskOfferReplyBytes;

	}
	
	class RunningRequestsSnapshot {
		
		int expectedTransfersOutCHK;
		int expectedTransfersInCHK;
		int expectedTransfersOutSSK;
		int expectedTransfersInSSK;
		int totalRequests;
		final boolean realTimeFlag;
		
		RunningRequestsSnapshot(Node node, boolean realTimeFlag, boolean ignoreLocalVsRemote, int transfersPerInsert) {
			int transfersInSSK = 0;
			int transfersOutSSK = 0;
			int transfersInCHK = 0;
			int transfersOutCHK = 0;
			CountedRequests count;
			count = node.countRequests(true, false, false, false, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote);
			transfersInCHK += count.expectedTransfersIn; transfersOutCHK += count.expectedTransfersOut;
			count = node.countRequests(true, true, false, false, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote);
			transfersInSSK += count.expectedTransfersIn; transfersOutSSK += count.expectedTransfersOut;
			count = node.countRequests(true, false, true, false, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote);
			transfersInCHK += count.expectedTransfersIn; transfersOutCHK += count.expectedTransfersOut;
			count = node.countRequests(true, true, true, false, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote);
			transfersInSSK += count.expectedTransfersIn; transfersOutSSK += count.expectedTransfersOut;
			count = node.countRequests(false, false, false, false, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote);
			transfersInCHK += count.expectedTransfersIn; transfersOutCHK += count.expectedTransfersOut;
			count = node.countRequests(false, true, false, false, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote);
			transfersInSSK += count.expectedTransfersIn; transfersOutSSK += count.expectedTransfersOut;
			count = node.countRequests(false, false, true, false, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote);
			transfersInCHK += count.expectedTransfersIn; transfersOutCHK += count.expectedTransfersOut;
			count = node.countRequests(false, true, true, false, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote);
			transfersInSSK += count.expectedTransfersIn; transfersOutSSK += count.expectedTransfersOut;
			count = node.countRequests(false, false, false, true, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote);
			transfersInCHK += count.expectedTransfersIn; transfersOutCHK += count.expectedTransfersOut;
			count = node.countRequests(false, true, false, true, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote);
			transfersInSSK += count.expectedTransfersIn; transfersOutSSK += count.expectedTransfersOut;
			this.expectedTransfersInCHK = transfersInCHK;
			this.expectedTransfersInSSK = transfersInSSK;
			this.expectedTransfersOutCHK = transfersOutCHK;
			this.expectedTransfersOutSSK = transfersOutSSK;
			this.realTimeFlag = realTimeFlag;
		}
		
		/**
		 * @param node
		 * @param source
		 * @param requestsToNode If true, count requests sent to the node and currently
		 * running. If false, count requests originated by the node.
		 */
		RunningRequestsSnapshot(Node node, PeerNode source, boolean requestsToNode, boolean realTimeFlag, boolean ignoreLocalVsRemote, int transfersPerInsert) {
			int transfersInSSK = 0;
			int transfersOutSSK = 0;
			int transfersInCHK = 0;
			int transfersOutCHK = 0;
			int reqs = 0;
			CountedRequests count;
			count = node.countRequests(source, requestsToNode, true, false, false, false, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote);
			transfersInCHK += count.expectedTransfersIn; transfersOutCHK += count.expectedTransfersOut;
			reqs += count.total;
			count = node.countRequests(source, requestsToNode, true, true, false, false, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote);
			transfersInSSK += count.expectedTransfersIn; transfersOutSSK += count.expectedTransfersOut;
			reqs += count.total;
			count = node.countRequests(source, requestsToNode, true, false, true, false, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote);
			transfersInCHK += count.expectedTransfersIn; transfersOutCHK += count.expectedTransfersOut;
			reqs += count.total;
			count = node.countRequests(source, requestsToNode, true, true, true, false, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote);
			transfersInSSK += count.expectedTransfersIn; transfersOutSSK += count.expectedTransfersOut;
			reqs += count.total;
			count = node.countRequests(source, requestsToNode, false, false, false, false, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote);
			transfersInCHK += count.expectedTransfersIn; transfersOutCHK += count.expectedTransfersOut;
			reqs += count.total;
			count = node.countRequests(source, requestsToNode, false, true, false, false, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote);
			transfersInSSK += count.expectedTransfersIn; transfersOutSSK += count.expectedTransfersOut;
			reqs += count.total;
			count = node.countRequests(source, requestsToNode, false, false, true, false, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote);
			transfersInCHK += count.expectedTransfersIn; transfersOutCHK += count.expectedTransfersOut;
			reqs += count.total;
			count = node.countRequests(source, requestsToNode, false, true, true, false, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote);
			transfersInSSK += count.expectedTransfersIn; transfersOutSSK += count.expectedTransfersOut;
			reqs += count.total;
			count = node.countRequests(source, requestsToNode, false, false, false, true, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote);
			transfersInCHK += count.expectedTransfersIn; transfersOutCHK += count.expectedTransfersOut;
			reqs += count.total;
			count = node.countRequests(source, requestsToNode, false, true, false, true, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote);
			transfersInSSK += count.expectedTransfersIn; transfersOutSSK += count.expectedTransfersOut;
			reqs += count.total;
			this.expectedTransfersInCHK = transfersInCHK;
			this.expectedTransfersInSSK = transfersInSSK;
			this.expectedTransfersOutCHK = transfersOutCHK;
			this.expectedTransfersOutSSK = transfersOutSSK;
			this.totalRequests = reqs;
			this.realTimeFlag = realTimeFlag;
		}

		public RunningRequestsSnapshot(PeerLoadStats stats) {
			// Assume they are all remote.
			this.expectedTransfersInCHK = stats.expectedTransfersInCHK;
			this.expectedTransfersInSSK = stats.expectedTransfersInSSK;
			this.expectedTransfersOutCHK = stats.expectedTransfersOutCHK;
			this.expectedTransfersOutSSK = stats.expectedTransfersOutSSK;
			this.totalRequests = stats.totalRequests;
			this.realTimeFlag = stats.realTime;
		}

		public void decrement(boolean isSSK, boolean isInsert,
				boolean isOfferReply, int transfersOutPerInsert) {
			if(isInsert) {
				if(isSSK)
					expectedTransfersOutSSK -= transfersOutPerInsert;
				else
					expectedTransfersOutCHK -= transfersOutPerInsert;
			} else {
				if(isSSK)
					expectedTransfersOutSSK--;
				else
					expectedTransfersOutCHK--;
			}
			if(!isOfferReply) {
				if(isSSK)
					expectedTransfersInSSK--;
				else
					expectedTransfersInCHK--;
			}
			totalRequests--;
		}

		public void log() {
			log(null);
		}
		
		public void log(PeerNode source) {
			Logger.minor(this, "Running (adjusted): CHK in: "+expectedTransfersInCHK+" out: "+expectedTransfersOutCHK+
					" SSK in: "+expectedTransfersInSSK+" out: "+expectedTransfersOutSSK
					+" realtime="+realTimeFlag+(source == null ? "" : (" for "+source)));
		}

		public double calculate(boolean ignoreLocalVsRemoteBandwidthLiability,
				ByteCountersSnapshot byteCountersSent, boolean input) {
			
			if(input)
				return this.expectedTransfersInCHK * 32768 +
					this.expectedTransfersInSSK * 2048;
			else
				return this.expectedTransfersOutCHK * 32768 +
					this.expectedTransfersOutSSK * 2048;
		}

		/**
		 * @return The number of requests running or -1 if not known (remote doesn't tell us).
		 */
		public int totalRequests() {
			return totalRequests;
		}

	}
	
	static class RejectReason {
		public final String name;
		/** If true, rejected because of preemptive bandwidth limiting, i.e. "soft", at least somewhat predictable, can be retried.
		 * If false, hard rejection, should backoff and not retry. */
		public final boolean soft;
		RejectReason(String n, boolean s) {
			name = n;
			soft = s;
		}
		public String toString() {
			return (soft ? "SOFT" : "HARD") + ":" + name;
		}
	}
	
	/* return reject reason as string if should reject, otherwise return null */
	public RejectReason shouldRejectRequest(boolean canAcceptAnyway, boolean isInsert, boolean isSSK, boolean isLocal, boolean isOfferReply, PeerNode source, boolean hasInStore, boolean preferInsert, boolean realTimeFlag) {
		if(logMINOR) dumpByteCostAverages();

		int threadCount = getActiveThreadCount();
		if(threadLimit < threadCount) {
			pInstantRejectIncoming.report(1.0);
			rejected(">threadLimit", isLocal);
			return new RejectReason(">threadLimit ("+threadCount+'/'+threadLimit+')', false);
		}

		double bwlimitDelayTime = throttledPacketSendAverage.currentValue();

		long[] total = node.collector.getTotalIO();
		long totalSent = total[0];
		long totalOverhead = getSentOverhead();
		long uptime = node.getUptime();
		long now = System.currentTimeMillis();
		
		double nonOverheadFraction = getNonOverheadFraction(totalSent, totalOverhead, uptime, now);
		
		// If no recent reports, no packets have been sent; correct the average downwards.
		double pingTime;
		pingTime = nodePinger.averagePingTime();
		synchronized(this) {
			// Round trip time
			if(pingTime > maxPingTime) {
				if((now - lastAcceptedRequest > MAX_INTERREQUEST_TIME) && canAcceptAnyway) {
					if(logMINOR) Logger.minor(this, "Accepting request anyway (take one every 10 secs to keep bwlimitDelayTime updated)");
				} else {
					pInstantRejectIncoming.report(1.0);
					rejected(">MAX_PING_TIME", isLocal);
					return new RejectReason(">MAX_PING_TIME ("+TimeUtil.formatTime((long)pingTime, 2, true)+ ')', false);
				}
			} else if(pingTime > subMaxPingTime) {
				double x = ((pingTime - subMaxPingTime)) / (maxPingTime - subMaxPingTime);
				if(randomLessThan(x, preferInsert)) {
					pInstantRejectIncoming.report(1.0);
					rejected(">SUB_MAX_PING_TIME", isLocal);
					return new RejectReason(">SUB_MAX_PING_TIME ("+TimeUtil.formatTime((long)pingTime, 2, true)+ ')', false);
				}
			}

			// Bandwidth limited packets
			if(bwlimitDelayTime > MAX_THROTTLE_DELAY) {
				if((now - lastAcceptedRequest > MAX_INTERREQUEST_TIME) && canAcceptAnyway) {
					if(logMINOR) Logger.minor(this, "Accepting request anyway (take one every 10 secs to keep bwlimitDelayTime updated)");
				} else {
					pInstantRejectIncoming.report(1.0);
					rejected(">MAX_THROTTLE_DELAY", isLocal);
					return new RejectReason(">MAX_THROTTLE_DELAY ("+TimeUtil.formatTime((long)bwlimitDelayTime, 2, true)+ ')', false);
				}
			} else if(bwlimitDelayTime > SUB_MAX_THROTTLE_DELAY) {
				double x = ((bwlimitDelayTime - SUB_MAX_THROTTLE_DELAY)) / (MAX_THROTTLE_DELAY - SUB_MAX_THROTTLE_DELAY);
				if(randomLessThan(x, preferInsert)) {
					pInstantRejectIncoming.report(1.0);
					rejected(">SUB_MAX_THROTTLE_DELAY", isLocal);
					return new RejectReason(">SUB_MAX_THROTTLE_DELAY ("+TimeUtil.formatTime((long)bwlimitDelayTime, 2, true)+ ')', false);
				}
			}

		}

		// Successful cluster timeout protection.
		// Reject request if the result of all our current requests completing simultaneously would be that
		// some of them timeout.
		
		int transfersPerInsert = outwardTransfersPerInsert();
		
		RunningRequestsSnapshot requestsSnapshot = new RunningRequestsSnapshot(node, realTimeFlag, ignoreLocalVsRemoteBandwidthLiability, transfersPerInsert);
		
		if(!isLocal) {
			// If not local, is already locked.
			// So we need to decrement the relevant value, to counteract this and restore the SSK:CHK balance.
			requestsSnapshot.decrement(isSSK, isInsert, isOfferReply, transfersPerInsert);
		}

		if(logMINOR)
			requestsSnapshot.log();
		
		long limit = realTimeFlag ? BANDWIDTH_LIABILITY_LIMIT_SECONDS_REALTIME : BANDWIDTH_LIABILITY_LIMIT_SECONDS_BULK;
		
		// Allow a bit more if the data is in the store and can therefore be served immediately.
		// This should improve performance.
		if(hasInStore) {
			limit += 10;
			if(logMINOR) Logger.minor(this, "Maybe accepting extra request due to it being in datastore (limit now "+limit+"s)...");
		}
		
		ByteCountersSnapshot byteCountersSent = new ByteCountersSnapshot(false);
		
		// Multiply by limit: X seconds at full power should be able to clear the transfers even if all the requests succeed.
		
		String ret = checkBandwidthLiability(getOutputBandwidthUpperLimit(totalSent, totalOverhead, uptime, limit, nonOverheadFraction), byteCountersSent, requestsSnapshot, false, limit,
				source, isLocal, isSSK, isInsert, isOfferReply, realTimeFlag, transfersPerInsert);  
		if(ret != null) return new RejectReason(ret, true);
		
		ByteCountersSnapshot byteCountersReceived = new ByteCountersSnapshot(true);
		
		ret = checkBandwidthLiability(getInputBandwidthUpperLimit(limit), byteCountersReceived, requestsSnapshot, true, limit,
				source, isLocal, isSSK, isInsert, isOfferReply, realTimeFlag, transfersPerInsert);  
		if(ret != null) return new RejectReason(ret, true);
		
		// Do we have the bandwidth?
		double expected = this.getThrottle(isLocal, isInsert, isSSK, true).currentValue();
		int expectedSent = (int)Math.max(expected / nonOverheadFraction, 0);
		if(logMINOR)
			Logger.minor(this, "Expected sent bytes: "+expected+" -> "+expectedSent);
		if(!requestOutputThrottle.instantGrab(expectedSent)) {
			pInstantRejectIncoming.report(1.0);
			rejected("Insufficient output bandwidth", isLocal);
			return new RejectReason("Insufficient output bandwidth", false);
		}
		expected = this.getThrottle(isLocal, isInsert, isSSK, false).currentValue();
		int expectedReceived = (int)Math.max(expected, 0);
		if(logMINOR)
			Logger.minor(this, "Expected received bytes: "+expectedReceived);
		if(!requestInputThrottle.instantGrab(expectedReceived)) {
			requestOutputThrottle.recycle(expectedSent);
			pInstantRejectIncoming.report(1.0);
			rejected("Insufficient input bandwidth", isLocal);
			return new RejectReason("Insufficient input bandwidth", false);
		}

		if(source != null) {
			if(source.getMessageQueueLengthBytes() > MAX_PEER_QUEUE_BYTES) {
				rejected(">MAX_PEER_QUEUE_BYTES", isLocal);
				return new RejectReason("Too many message bytes queued for peer", false);
			}
			if(source.getProbableSendQueueTime() > MAX_PEER_QUEUE_TIME) {
				rejected(">MAX_PEER_QUEUE_TIME", isLocal);
				return new RejectReason("Peer's queue will take too long to transfer", false);
			}
		}

		synchronized(this) {
			if(logMINOR) Logger.minor(this, "Accepting request? (isSSK="+isSSK+")");
			lastAcceptedRequest = now;
		}

		pInstantRejectIncoming.report(0.0);

		// Accept
		return null;
	}
	
	public int outwardTransfersPerInsert() {
		// FIXME compute an average
		return 1;
	}

	private double getInputBandwidthUpperLimit(long limit) {
		return node.getInputBandwidthLimit() * limit;
	}

	private double getNonOverheadFraction(long totalSent, long totalOverhead,
			long uptime, long now) {
		
		/** The fraction of output bytes which are used for requests */
		// FIXME consider using a shorter average
		// FIXME what happens when the bwlimit changes?
		
		double totalCouldSend = Math.max(totalSent,
				((double)((node.getOutputBandwidthLimit() * uptime))/1000.0));
		double nonOverheadFraction = (totalCouldSend - totalOverhead) / totalCouldSend;
		long timeFirstAnyConnections = peers.timeFirstAnyConnections;
		if(timeFirstAnyConnections > 0) {
			long time = now - timeFirstAnyConnections;
			if(time < DEFAULT_ONLY_PERIOD) {
				nonOverheadFraction = DEFAULT_OVERHEAD;
				if(logMINOR) Logger.minor(this, "Adjusted non-overhead fraction: "+nonOverheadFraction);
			} else if(time < DEFAULT_ONLY_PERIOD + DEFAULT_TRANSITION_PERIOD) {
				time -= DEFAULT_ONLY_PERIOD;
				nonOverheadFraction = (time * nonOverheadFraction + 
					(DEFAULT_TRANSITION_PERIOD - time) * DEFAULT_OVERHEAD) / DEFAULT_TRANSITION_PERIOD;
				if(logMINOR) Logger.minor(this, "Adjusted non-overhead fraction: "+nonOverheadFraction);
			}
		}
		if(nonOverheadFraction < MIN_NON_OVERHEAD) {
			// If there's been an auto-update, we may have used a vast amount of bandwidth for it.
			// Also, if things have broken, our overhead might be above our bandwidth limit,
			// especially on a slow node.
			
			// So impose a minimum of 20% of the bandwidth limit.
			// This will ensure we don't get stuck in any situation where all our bandwidth is overhead,
			// and we don't accept any requests because of that, so it remains that way...
			Logger.error(this, "Non-overhead fraction is "+nonOverheadFraction+" - assuming this is self-inflicted and using default");
			nonOverheadFraction = MIN_NON_OVERHEAD;
		}
		if(nonOverheadFraction > 1.0) {
			Logger.error(this, "Non-overhead fraction is >1.0!!!");
			return 1.0;
		}
		return nonOverheadFraction;
	}

	private double getOutputBandwidthUpperLimit(long totalSent, long totalOverhead, long uptime, long limit, double nonOverheadFraction) {
		double sentOverheadPerSecond = (totalOverhead*1000.0) / (uptime);
		
		if(logMINOR) Logger.minor(this, "Output rate: "+(totalSent*1000.0)/uptime+" overhead rate "+sentOverheadPerSecond+" non-overhead fraction "+nonOverheadFraction);
		
		double outputAvailablePerSecond = node.getOutputBandwidthLimit() * nonOverheadFraction;
		
		if(logMINOR) Logger.minor(this, "Overhead per second: "+sentOverheadPerSecond+" bwlimit: "+node.getOutputBandwidthLimit()+" => output available per second: "+outputAvailablePerSecond+" but minimum of "+node.getOutputBandwidthLimit() / 5.0);
		
		return outputAvailablePerSecond * limit;
	}

	private String checkBandwidthLiability(double bandwidthAvailableOutputUpperLimit,
			ByteCountersSnapshot byteCountersSent,
			RunningRequestsSnapshot requestsSnapshot, boolean input, long limit,
			PeerNode source, boolean isLocal, boolean isSSK, boolean isInsert, boolean isOfferReply, boolean realTimeFlag, int transfersPerInsert) {
		String name = input ? "Input" : "Output";
		double bandwidthAvailableOutputLowerLimit = bandwidthAvailableOutputUpperLimit / 2;
		
		double bandwidthLiabilityOutput = requestsSnapshot.calculate(ignoreLocalVsRemoteBandwidthLiability, byteCountersSent, input);
		
		// Calculate the peer limit so the peer gets notified, even if we are going to ignore it.
		
		double thisAllocation = getPeerLimit(source, bandwidthAvailableOutputLowerLimit, input, realTimeFlag, false);
		
		// If over the upper limit, reject.
		
		if(logMINOR) Logger.minor(this, limit+" second limit: "+bandwidthAvailableOutputUpperLimit+" expected output liability: "+bandwidthLiabilityOutput);
		if(bandwidthLiabilityOutput > bandwidthAvailableOutputUpperLimit) {
			pInstantRejectIncoming.report(1.0);
			rejected(name+" bandwidth liability", isLocal);
			return name+" bandwidth liability ("+bandwidthLiabilityOutput+" > "+bandwidthAvailableOutputUpperLimit+")";
		}
		
		if(bandwidthLiabilityOutput > bandwidthAvailableOutputLowerLimit) {
			
			// Fair sharing between peers.
			
			if(logMINOR)
				Logger.minor(this, "Allocation ("+name+") for "+source+" is "+thisAllocation);
			
			double peerUsedBytes = getPeerBandwidthLiability(source, isSSK, isInsert, isOfferReply, byteCountersSent, realTimeFlag, ignoreLocalVsRemoteBandwidthLiability, transfersPerInsert, input);
			if(peerUsedBytes > thisAllocation) {
				rejected(name+" bandwidth liability: fairness between peers", isLocal);
				return name+" bandwidth liability: fairness between peers (peer "+source+" used "+peerUsedBytes+" allowed "+thisAllocation+")";
			}
			
			// Fair sharing between types should be implemented on the sender.
			// Implementing it here will interfere with fair sharing between peers' predictability: It can cause failures even when a peer is within its guaranteed allocation, and these failures can be caused by outside, unpredictable factors (i.e. actions of other peers).
		}
		return null;
	}

	private double getPeerLimit(PeerNode source, double bandwidthAvailableOutputLowerLimit, boolean input, boolean realTimeFlag, boolean dontTellPeer) {
		
		int peers = node.peers.countConnectedPeers();
		
		double thisAllocation;
		
		// FIXME: MAKE CONFIGURABLE AND SECLEVEL DEPENDANT!
		if(RequestStarter.LOCAL_REQUESTS_COMPETE_FAIRLY) {
			thisAllocation = bandwidthAvailableOutputLowerLimit / (peers + 1);
		} else {
			double totalAllocation = bandwidthAvailableOutputLowerLimit;
			// FIXME: MAKE CONFIGURABLE AND SECLEVEL DEPENDANT!
			double localAllocation = totalAllocation * 0.5;
			if(source == null)
				thisAllocation = localAllocation;
			else {
				totalAllocation -= localAllocation;
				thisAllocation = totalAllocation / peers;
			}
		}
		
		if(source != null && !dontTellPeer) {
			// FIXME tell local as well somehow?
			source.onSetPeerAllocation(input, (int)thisAllocation, realTimeFlag);
		}
		
		return thisAllocation;
		
	}

	private double getPeerBandwidthLiability(PeerNode source, boolean isSSK, boolean isInsert, boolean isOfferReply, ByteCountersSnapshot byteCounters, boolean realTimeFlag, boolean ignoreLocalVsRemote, int transfersOutPerInsert, boolean input) {
		RunningRequestsSnapshot requestsSnapshot = new RunningRequestsSnapshot(node, source, false, realTimeFlag, ignoreLocalVsRemote, transfersOutPerInsert);
		
		if(source != null) {
			requestsSnapshot.decrement(isSSK, isInsert, isOfferReply, transfersOutPerInsert);
		}
		
		requestsSnapshot.log(source);
		
		return requestsSnapshot.calculate(ignoreLocalVsRemoteBandwidthLiability, byteCounters, input);
	}

	/** @return True if we should reject the request.
	 * @param x The threshold. We should reject the request unless a random number is greater than this threshold.
	 * @param preferInsert If true, we allow 3 chances to pass the threshold.
	 */
	private boolean randomLessThan(double x, boolean preferInsert) {
		if(preferInsert) {
			// Three chances.
			for(int i=0;i<3;i++)
				if(hardRandom.nextDouble() >= x) return false;
			return true;
		} else {
			// One chance
		}
		return hardRandom.nextDouble() < x;
	}

	private void rejected(String reason, boolean isLocal) {
		if(!isLocal) preemptiveRejectReasons.inc(reason);
		else this.localPreemptiveRejectReasons.inc(reason);
	}

	private RunningAverage getThrottle(boolean isLocal, boolean isInsert, boolean isSSK, boolean isSent) {
		if(isLocal) {
			if(isInsert) {
				if(isSSK) {
					return isSent ? this.localSskInsertBytesSentAverage : this.localSskInsertBytesReceivedAverage;
				} else {
					return isSent ? this.localChkInsertBytesSentAverage : this.localChkInsertBytesReceivedAverage;
				}
			} else {
				if(isSSK) {
					return isSent ? this.localSskFetchBytesSentAverage : this.localSskFetchBytesReceivedAverage;
				} else {
					return isSent ? this.localChkFetchBytesSentAverage : this.localChkFetchBytesReceivedAverage;
				}
			}
		} else {
			if(isInsert) {
				if(isSSK) {
					return isSent ? this.remoteSskInsertBytesSentAverage : this.remoteSskInsertBytesReceivedAverage;
				} else {
					return isSent ? this.remoteChkInsertBytesSentAverage : this.remoteChkInsertBytesReceivedAverage;
				}
			} else {
				if(isSSK) {
					return isSent ? this.remoteSskFetchBytesSentAverage : this.remoteSskFetchBytesReceivedAverage;
				} else {
					return isSent ? this.remoteChkFetchBytesSentAverage : this.remoteChkFetchBytesReceivedAverage;
				}
			}
		}
	}

	private void dumpByteCostAverages() {
		Logger.minor(this, "Byte cost averages: REMOTE:"+
				" CHK insert "+remoteChkInsertBytesSentAverage.currentValue()+ '/' +remoteChkInsertBytesReceivedAverage.currentValue()+
				" SSK insert "+remoteSskInsertBytesSentAverage.currentValue()+ '/' +remoteSskInsertBytesReceivedAverage.currentValue()+
				" CHK fetch "+remoteChkFetchBytesSentAverage.currentValue()+ '/' +remoteChkFetchBytesReceivedAverage.currentValue()+
				" SSK fetch "+remoteSskFetchBytesSentAverage.currentValue()+ '/' +remoteSskFetchBytesReceivedAverage.currentValue());
		Logger.minor(this, "Byte cost averages: LOCAL:"+
				" CHK insert "+localChkInsertBytesSentAverage.currentValue()+ '/' +localChkInsertBytesReceivedAverage.currentValue()+
				" SSK insert "+localSskInsertBytesSentAverage.currentValue()+ '/' +localSskInsertBytesReceivedAverage.currentValue()+
				" CHK fetch "+localChkFetchBytesSentAverage.currentValue()+ '/' +localChkFetchBytesReceivedAverage.currentValue()+
				" SSK fetch "+localSskFetchBytesSentAverage.currentValue()+ '/' +localSskFetchBytesReceivedAverage.currentValue());
		Logger.minor(this, "Byte cost averages: SUCCESSFUL:"+
				" CHK insert "+successfulChkInsertBytesSentAverage.currentValue()+ '/' +successfulChkInsertBytesReceivedAverage.currentValue()+
				" SSK insert "+successfulSskInsertBytesSentAverage.currentValue()+ '/' +successfulSskInsertBytesReceivedAverage.currentValue()+
				" CHK fetch "+successfulChkFetchBytesSentAverage.currentValue()+ '/' +successfulChkFetchBytesReceivedAverage.currentValue()+
				" SSK fetch "+successfulSskFetchBytesSentAverage.currentValue()+ '/' +successfulSskFetchBytesReceivedAverage.currentValue()+
				" CHK offer reply "+successfulChkOfferReplyBytesSentAverage.currentValue()+ '/' +successfulChkOfferReplyBytesReceivedAverage.currentValue()+
				" SSK offer reply "+successfulSskOfferReplyBytesSentAverage.currentValue()+ '/' +successfulSskOfferReplyBytesReceivedAverage.currentValue());

	}

	public double getBwlimitDelayTime() {
		return throttledPacketSendAverage.currentValue();
	}

	public double getNodeAveragePingTime() {
		return nodePinger.averagePingTime();
	}

	public int getOpennetSizeEstimate(long timestamp) {
		if (node.opennet == null)
			return 0;
		return node.opennet.getNetworkSizeEstimate(timestamp);
	}
	public int getDarknetSizeEstimate(long timestamp) {
		return node.lm.getNetworkSizeEstimate( timestamp );
	}

	public Object[] getKnownLocations(long timestamp) {
		return node.lm.getKnownLocations( timestamp );
	}

	public double pRejectIncomingInstantly() {
		return pInstantRejectIncoming.currentValue();
	}

	/**
	 * Update peerManagerUserAlertStats if the timer has expired.
	 * Only called from PacketSender so doesn't need sync.
	 */
	public void maybeUpdatePeerManagerUserAlertStats(long now) {
		if(now > nextPeerManagerUserAlertStatsUpdateTime) {
			if(getBwlimitDelayTime() > MAX_BWLIMIT_DELAY_TIME_ALERT_THRESHOLD) {
				if(firstBwlimitDelayTimeThresholdBreak == 0) {
					firstBwlimitDelayTimeThresholdBreak = now;
				}
			} else {
				firstBwlimitDelayTimeThresholdBreak = 0;
			}
			if((firstBwlimitDelayTimeThresholdBreak != 0) && ((now - firstBwlimitDelayTimeThresholdBreak) >= MAX_BWLIMIT_DELAY_TIME_ALERT_DELAY)) {
				bwlimitDelayAlertRelevant = true;
			} else {
				bwlimitDelayAlertRelevant = false;
			}
			if(getNodeAveragePingTime() > 2*maxPingTime) {
				if(firstNodeAveragePingTimeThresholdBreak == 0) {
					firstNodeAveragePingTimeThresholdBreak = now;
				}
			} else {
				firstNodeAveragePingTimeThresholdBreak = 0;
			}
			if((firstNodeAveragePingTimeThresholdBreak != 0) && ((now - firstNodeAveragePingTimeThresholdBreak) >= MAX_NODE_AVERAGE_PING_TIME_ALERT_DELAY)) {
				nodeAveragePingAlertRelevant = true;
			} else {
				nodeAveragePingAlertRelevant = false;
			}
			if(logDEBUG) Logger.debug(this, "mUPMUAS: "+now+": "+getBwlimitDelayTime()+" >? "+MAX_BWLIMIT_DELAY_TIME_ALERT_THRESHOLD+" since "+firstBwlimitDelayTimeThresholdBreak+" ("+bwlimitDelayAlertRelevant+") "+getNodeAveragePingTime()+" >? "+MAX_NODE_AVERAGE_PING_TIME_ALERT_THRESHOLD+" since "+firstNodeAveragePingTimeThresholdBreak+" ("+nodeAveragePingAlertRelevant+ ')');
			nextPeerManagerUserAlertStatsUpdateTime = now + peerManagerUserAlertStatsUpdateInterval;
		}
	}

	public SimpleFieldSet persistThrottlesToFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.put("RemoteChkFetchBytesSentAverage", remoteChkFetchBytesSentAverage.exportFieldSet(true));
		fs.put("RemoteSskFetchBytesSentAverage", remoteSskFetchBytesSentAverage.exportFieldSet(true));
		fs.put("RemoteChkInsertBytesSentAverage", remoteChkInsertBytesSentAverage.exportFieldSet(true));
		fs.put("RemoteSskInsertBytesSentAverage", remoteSskInsertBytesSentAverage.exportFieldSet(true));
		fs.put("RemoteChkFetchBytesReceivedAverage", remoteChkFetchBytesReceivedAverage.exportFieldSet(true));
		fs.put("RemoteSskFetchBytesReceivedAverage", remoteSskFetchBytesReceivedAverage.exportFieldSet(true));
		fs.put("RemoteChkInsertBytesReceivedAverage", remoteChkInsertBytesReceivedAverage.exportFieldSet(true));
		fs.put("RemoteSskInsertBytesReceivedAverage", remoteSskInsertBytesReceivedAverage.exportFieldSet(true));
		fs.put("LocalChkFetchBytesSentAverage", localChkFetchBytesSentAverage.exportFieldSet(true));
		fs.put("LocalSskFetchBytesSentAverage", localSskFetchBytesSentAverage.exportFieldSet(true));
		fs.put("LocalChkInsertBytesSentAverage", localChkInsertBytesSentAverage.exportFieldSet(true));
		fs.put("LocalSskInsertBytesSentAverage", localSskInsertBytesSentAverage.exportFieldSet(true));
		fs.put("LocalChkFetchBytesReceivedAverage", localChkFetchBytesReceivedAverage.exportFieldSet(true));
		fs.put("LocalSskFetchBytesReceivedAverage", localSskFetchBytesReceivedAverage.exportFieldSet(true));
		fs.put("LocalChkInsertBytesReceivedAverage", localChkInsertBytesReceivedAverage.exportFieldSet(true));
		fs.put("LocalSskInsertBytesReceivedAverage", localSskInsertBytesReceivedAverage.exportFieldSet(true));
		fs.put("SuccessfulChkFetchBytesSentAverage", successfulChkFetchBytesSentAverage.exportFieldSet(true));
		fs.put("SuccessfulSskFetchBytesSentAverage", successfulSskFetchBytesSentAverage.exportFieldSet(true));
		fs.put("SuccessfulChkInsertBytesSentAverage", successfulChkInsertBytesSentAverage.exportFieldSet(true));
		fs.put("SuccessfulSskInsertBytesSentAverage", successfulSskInsertBytesSentAverage.exportFieldSet(true));
		fs.put("SuccessfulChkOfferReplyBytesSentAverage", successfulChkOfferReplyBytesSentAverage.exportFieldSet(true));
		fs.put("SuccessfulSskOfferReplyBytesSentAverage", successfulSskOfferReplyBytesSentAverage.exportFieldSet(true));
		fs.put("SuccessfulChkFetchBytesReceivedAverage", successfulChkFetchBytesReceivedAverage.exportFieldSet(true));
		fs.put("SuccessfulSskFetchBytesReceivedAverage", successfulSskFetchBytesReceivedAverage.exportFieldSet(true));
		fs.put("SuccessfulChkInsertBytesReceivedAverage", successfulChkInsertBytesReceivedAverage.exportFieldSet(true));
		fs.put("SuccessfulSskInsertBytesReceivedAverage", successfulSskInsertBytesReceivedAverage.exportFieldSet(true));
		fs.put("SuccessfulChkOfferReplyBytesReceivedAverage", successfulChkOfferReplyBytesReceivedAverage.exportFieldSet(true));
		fs.put("SuccessfulSskOfferReplyBytesReceivedAverage", successfulSskOfferReplyBytesReceivedAverage.exportFieldSet(true));

		//These are not really part of the 'throttling' data, but are also running averages which should be persisted
		fs.put("AverageCacheCHKLocation", avgCacheCHKLocation.exportFieldSet(true));
		fs.put("AverageStoreCHKLocation", avgStoreCHKLocation.exportFieldSet(true));
		fs.put("AverageSlashdotCacheCHKLocation",avgSlashdotCacheCHKLocation.exportFieldSet(true));
		fs.put("AverageClientCacheCHKLocation",avgClientCacheCHKLocation.exportFieldSet(true));

		fs.put("AverageCacheCHKSuccessLocation", avgCacheCHKSuccess.exportFieldSet(true));
		fs.put("AverageSlashdotCacheCHKSuccessLocation", avgSlashdotCacheCHKSucess.exportFieldSet(true));
		fs.put("AverageClientCacheCHKSuccessLocation", avgClientCacheCHKSuccess.exportFieldSet(true));
		fs.put("AverageStoreCHKSuccessLocation", avgStoreCHKSuccess.exportFieldSet(true));

		fs.put("AverageCacheSSKLocation", avgCacheSSKLocation.exportFieldSet(true));
		fs.put("AverageStoreSSKLocation", avgStoreSSKLocation.exportFieldSet(true));
		fs.put("AverageSlashdotCacheSSKLocation",avgSlashdotCacheSSKLocation.exportFieldSet(true));
		fs.put("AverageClientCacheSSKLocation",avgClientCacheSSKLocation.exportFieldSet(true));

		fs.put("AverageCacheSSKSuccessLocation", avgCacheSSKSuccess.exportFieldSet(true));
		fs.put("AverageSlashdotCacheSSKSuccessLocation", avgSlashdotCacheSSKSuccess.exportFieldSet(true));
		fs.put("AverageClientCacheSSKSuccessLocation", avgClientCacheSSKSuccess.exportFieldSet(true));
		fs.put("AverageStoreSSKSuccessLocation", avgStoreSSKSuccess.exportFieldSet(true));

		fs.put("AverageRequestLocation", avgRequestLocation.exportFieldSet(true));

		return fs;
	}

	/**
	 * Update the node-wide bandwidth I/O stats if the timer has expired
	 */
	public void maybeUpdateNodeIOStats(long now) {
		if(now > nextNodeIOStatsUpdateTime) {
			long[] io_stats = node.collector.getTotalIO();
			long outdiff;
			long indiff;
			synchronized(ioStatSync) {
				previous_output_stat = last_output_stat;
				previous_input_stat = last_input_stat;
				previous_io_stat_time = last_io_stat_time;
				last_output_stat = io_stats[ 0 ];
				last_input_stat = io_stats[ 1 ];
				last_io_stat_time = now;
				outdiff = last_output_stat - previous_output_stat;
				indiff = last_input_stat - previous_input_stat;
			}
			if(logMINOR)
				Logger.minor(this, "Last 2 seconds: input: "+indiff+" output: "+outdiff);
			nextNodeIOStatsUpdateTime = now + nodeIOStatsUpdateInterval;
		}
	}

	public long[] getNodeIOStats() {
		long[] result = new long[6];
		synchronized(ioStatSync) {
			result[ 0 ] = previous_output_stat;
			result[ 1 ] = previous_input_stat;
			result[ 2 ] = previous_io_stat_time;
			result[ 3 ] = last_output_stat;
			result[ 4 ] = last_input_stat;
			result[ 5 ] = last_io_stat_time;
		}
		return result;
	}

	public void waitUntilNotOverloaded(boolean isInsert) {
		while(threadLimit < getActiveThreadCount()){
			try{
				Thread.sleep(5000);
			} catch (InterruptedException e) {}
		}
	}

	public int getActiveThreadCount() {
		return rootThreadGroup.activeCount() - node.executor.getWaitingThreadsCount();
	}

	public int[] getActiveThreadsByPriority() {
		return activeThreadsByPriorities;
	}

	public int[] getWaitingThreadsByPriority() {
		return waitingThreadsByPriorities;
	}

	public int getThreadLimit() {
		return threadLimit;
	}

	public SimpleFieldSet exportVolatileFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		long now = System.currentTimeMillis();
		fs.put("isUsingWrapper", node.isUsingWrapper());
		long nodeUptimeSeconds = 0;
		synchronized(this) {
			fs.put("startupTime", node.startupTime);
			nodeUptimeSeconds = (now - node.startupTime) / 1000;
			if (nodeUptimeSeconds == 0) nodeUptimeSeconds = 1;	// prevent division by zero
			fs.put("uptimeSeconds", nodeUptimeSeconds);
		}
		fs.put("averagePingTime", getNodeAveragePingTime());
		fs.put("bwlimitDelayTime", getBwlimitDelayTime());

		// Network Size
		fs.put("opennetSizeEstimateSession", getOpennetSizeEstimate(-1));
		fs.put("networkSizeEstimateSession", getDarknetSizeEstimate(-1));
		for (int t = 1 ; t < 7; t++) {
			int hour = t * 24;
			long limit = now - t * ((long) 24 * 60 * 60 * 1000);

			fs.put("opennetSizeEstimate"+hour+"hourRecent", getOpennetSizeEstimate(limit));
			fs.put("networkSizeEstimate"+hour+"hourRecent", getDarknetSizeEstimate(limit));
		}
		
		fs.put("routingMissDistanceLocal", routingMissDistanceLocal.currentValue());
		fs.put("routingMissDistanceRemote", routingMissDistanceRemote.currentValue());
		fs.put("routingMissDistanceOverall", routingMissDistanceOverall.currentValue());
		fs.put("backedOffPercent", backedOffPercent.currentValue());
		fs.put("pInstantReject", pRejectIncomingInstantly());
		fs.put("unclaimedFIFOSize", node.usm.getUnclaimedFIFOSize());

		/* gather connection statistics */
		PeerNodeStatus[] peerNodeStatuses = peers.getPeerNodeStatuses(true);
		int numberOfSeedServers = 0;
		int numberOfSeedClients = 0;

		for (PeerNodeStatus peerNodeStatus: peerNodeStatuses) {
			if (peerNodeStatus.isSeedServer())
				numberOfSeedServers++;
			if (peerNodeStatus.isSeedClient())
				numberOfSeedClients++;
		}

		int numberOfConnected = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_CONNECTED);
		int numberOfRoutingBackedOff = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_ROUTING_BACKED_OFF);
		int numberOfTooNew = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_TOO_NEW);
		int numberOfTooOld = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_TOO_OLD);
		int numberOfDisconnected = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_DISCONNECTED);
		int numberOfNeverConnected = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_NEVER_CONNECTED);
		int numberOfDisabled = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_DISABLED);
		int numberOfBursting = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_BURSTING);
		int numberOfListening = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_LISTENING);
		int numberOfListenOnly = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_LISTEN_ONLY);

		int numberOfSimpleConnected = numberOfConnected + numberOfRoutingBackedOff;
		int numberOfNotConnected = numberOfTooNew + numberOfTooOld + numberOfDisconnected + numberOfNeverConnected + numberOfDisabled + numberOfBursting + numberOfListening + numberOfListenOnly;

		fs.put("numberOfSeedServers", numberOfSeedServers);
		fs.put("numberOfSeedClients", numberOfSeedClients);
		fs.put("numberOfConnected", numberOfConnected);
		fs.put("numberOfRoutingBackedOff", numberOfRoutingBackedOff);
		fs.put("numberOfTooNew", numberOfTooNew);
		fs.put("numberOfTooOld", numberOfTooOld);
		fs.put("numberOfDisconnected", numberOfDisconnected);
		fs.put("numberOfNeverConnected", numberOfNeverConnected);
		fs.put("numberOfDisabled", numberOfDisabled);
		fs.put("numberOfBursting", numberOfBursting);
		fs.put("numberOfListening", numberOfListening);
		fs.put("numberOfListenOnly", numberOfListenOnly);

		fs.put("numberOfSimpleConnected", numberOfSimpleConnected);
		fs.put("numberOfNotConnected", numberOfNotConnected);

		fs.put("numberOfTransferringRequestSenders", node.getNumTransferringRequestSenders());
		fs.put("numberOfARKFetchers", node.getNumARKFetchers());

		long[] total = node.collector.getTotalIO();
		long total_output_rate = (total[0]) / nodeUptimeSeconds;
		long total_input_rate = (total[1]) / nodeUptimeSeconds;
		long totalPayloadOutput = node.getTotalPayloadSent();
		long total_payload_output_rate = totalPayloadOutput / nodeUptimeSeconds;
		int total_payload_output_percent = (total[0]==0)?-1:(int) (100 * totalPayloadOutput / total[0]);
		fs.put("totalOutputBytes", total[0]);
		fs.put("totalOutputRate", total_output_rate);
		fs.put("totalPayloadOutputBytes", totalPayloadOutput);
		fs.put("totalPayloadOutputRate", total_payload_output_rate);
		fs.put("totalPayloadOutputPercent", total_payload_output_percent);
		fs.put("totalInputBytes", total[1]);
		fs.put("totalInputRate", total_input_rate);

		long[] rate = getNodeIOStats();
		long deltaMS = (rate[5] - rate[2]);
		double recent_output_rate = deltaMS==0?0:(1000.0 * (rate[3] - rate[0]) / deltaMS);
		double recent_input_rate = deltaMS==0?0:(1000.0 * (rate[4] - rate[1]) / deltaMS);
		fs.put("recentOutputRate", recent_output_rate);
		fs.put("recentInputRate", recent_input_rate);

		String [] routingBackoffReasons = peers.getPeerNodeRoutingBackoffReasons();
		if(routingBackoffReasons.length != 0) {
			for(int i=0;i<routingBackoffReasons.length;i++) {
				fs.put("numberWithRoutingBackoffReasons." + routingBackoffReasons[i], peers.getPeerNodeRoutingBackoffReasonSize(routingBackoffReasons[i]));
			}
		}

		double swaps = node.getSwaps();
		double noSwaps = node.getNoSwaps();
		double numberOfRemotePeerLocationsSeenInSwaps = node.getNumberOfRemotePeerLocationsSeenInSwaps();
		fs.putSingle("numberOfRemotePeerLocationsSeenInSwaps", Double.toString(numberOfRemotePeerLocationsSeenInSwaps));
		double avgConnectedPeersPerNode = 0.0;
		if ((numberOfRemotePeerLocationsSeenInSwaps > 0.0) && ((swaps > 0.0) || (noSwaps > 0.0))) {
			avgConnectedPeersPerNode = numberOfRemotePeerLocationsSeenInSwaps/(swaps+noSwaps);
		}
		fs.putSingle("avgConnectedPeersPerNode", Double.toString(avgConnectedPeersPerNode));

		int startedSwaps = node.getStartedSwaps();
		int swapsRejectedAlreadyLocked = node.getSwapsRejectedAlreadyLocked();
		int swapsRejectedNowhereToGo = node.getSwapsRejectedNowhereToGo();
		int swapsRejectedRateLimit = node.getSwapsRejectedRateLimit();
		int swapsRejectedRecognizedID = node.getSwapsRejectedRecognizedID();
		double locationChangePerSession = node.getLocationChangeSession();
		double locationChangePerSwap = 0.0;
		double locationChangePerMinute = 0.0;
		double swapsPerMinute = 0.0;
		double noSwapsPerMinute = 0.0;
		double swapsPerNoSwaps = 0.0;
		if (swaps > 0) {
			locationChangePerSwap = locationChangePerSession/swaps;
		}
		if ((swaps > 0.0) && (nodeUptimeSeconds >= 60)) {
			locationChangePerMinute = locationChangePerSession/(nodeUptimeSeconds/60.0);
		}
		if ((swaps > 0.0) && (nodeUptimeSeconds >= 60)) {
			swapsPerMinute = swaps/(nodeUptimeSeconds/60.0);
		}
		if ((noSwaps > 0.0) && (nodeUptimeSeconds >= 60)) {
			noSwapsPerMinute = noSwaps/(nodeUptimeSeconds/60.0);
		}
		if ((swaps > 0.0) && (noSwaps > 0.0)) {
			swapsPerNoSwaps = swaps/noSwaps;
		}
		fs.put("locationChangePerSession", locationChangePerSession);
		fs.put("locationChangePerSwap", locationChangePerSwap);
		fs.put("locationChangePerMinute", locationChangePerMinute);
		fs.put("swapsPerMinute", swapsPerMinute);
		fs.put("noSwapsPerMinute", noSwapsPerMinute);
		fs.put("swapsPerNoSwaps", swapsPerNoSwaps);
		fs.put("swaps", swaps);
		fs.put("noSwaps", noSwaps);
		fs.put("startedSwaps", startedSwaps);
		fs.put("swapsRejectedAlreadyLocked", swapsRejectedAlreadyLocked);
		fs.put("swapsRejectedNowhereToGo", swapsRejectedNowhereToGo);
		fs.put("swapsRejectedRateLimit", swapsRejectedRateLimit);
		fs.put("swapsRejectedRecognizedID", swapsRejectedRecognizedID);
		long fix32kb = 32 * 1024;
		long cachedKeys = node.getChkDatacache().keyCount();
		long cachedSize = cachedKeys * fix32kb;
		long storeKeys = node.getChkDatastore().keyCount();
		long storeSize = storeKeys * fix32kb;
		long overallKeys = cachedKeys + storeKeys;
		long overallSize = cachedSize + storeSize;

		long maxOverallKeys = node.getMaxTotalKeys();
		long maxOverallSize = maxOverallKeys * fix32kb;

		double percentOverallKeysOfMax = (double)(overallKeys*100)/(double)maxOverallKeys;

		long cachedStoreHits = node.getChkDatacache().hits();
		long cachedStoreMisses = node.getChkDatacache().misses();
		long cacheAccesses = cachedStoreHits + cachedStoreMisses;
		double percentCachedStoreHitsOfAccesses = (double)(cachedStoreHits*100) / (double)cacheAccesses;
		long storeHits = node.getChkDatastore().hits();
		long storeMisses = node.getChkDatastore().misses();
		long storeAccesses = storeHits + storeMisses;
		double percentStoreHitsOfAccesses = (double)(storeHits*100) / (double)storeAccesses;
		long overallAccesses = storeAccesses + cacheAccesses;
		double avgStoreAccessRate = (double)overallAccesses/(double)nodeUptimeSeconds;

		fs.put("cachedKeys", cachedKeys);
		fs.put("cachedSize", cachedSize);
		fs.put("storeKeys", storeKeys);
		fs.put("storeSize", storeSize);
		fs.put("overallKeys", overallKeys);
		fs.put("overallSize", overallSize);
		fs.put("maxOverallKeys", maxOverallKeys);
		fs.put("maxOverallSize", maxOverallSize);
		fs.put("percentOverallKeysOfMax", percentOverallKeysOfMax);
		fs.put("cachedStoreHits", cachedStoreHits);
		fs.put("cachedStoreMisses", cachedStoreMisses);
		fs.put("cacheAccesses", cacheAccesses);
		fs.put("percentCachedStoreHitsOfAccesses", percentCachedStoreHitsOfAccesses);
		fs.put("storeHits", storeHits);
		fs.put("storeMisses", storeMisses);
		fs.put("storeAccesses", storeAccesses);
		fs.put("percentStoreHitsOfAccesses", percentStoreHitsOfAccesses);
		fs.put("overallAccesses", overallAccesses);
		fs.put("avgStoreAccessRate", avgStoreAccessRate);

		Runtime rt = Runtime.getRuntime();
		float freeMemory = rt.freeMemory();
		float totalMemory = rt.totalMemory();
		float maxMemory = rt.maxMemory();

		long usedJavaMem = (long)(totalMemory - freeMemory);
		long allocatedJavaMem = (long)totalMemory;
		long maxJavaMem = (long)maxMemory;
		int availableCpus = rt.availableProcessors();

		fs.put("freeJavaMemory", (long)freeMemory);
		fs.put("usedJavaMemory", usedJavaMem);
		fs.put("allocatedJavaMemory", allocatedJavaMem);
		fs.put("maximumJavaMemory", maxJavaMem);
		fs.put("availableCPUs", availableCpus);
		fs.put("runningThreadCount", getActiveThreadCount());

		fs.put("globalFetchPSuccess", globalFetchPSuccess.currentValue());
		fs.put("chkLocalFetchPSuccess", chkLocalFetchPSuccess.currentValue());
		fs.put("chkRemoteFetchPSuccess", chkRemoteFetchPSuccess.currentValue());
		fs.put("sskLocalFetchPSuccess", sskLocalFetchPSuccess.currentValue());
		fs.put("sskRemoteFetchPSuccess", sskRemoteFetchPSuccess.currentValue());
		fs.put("blockTransferPSuccess", blockTransferPSuccess.currentValue());
		fs.put("blockTransferFailTurtled", blockTransferFailTurtled.currentValue());
		fs.put("blockTransferFailTimeout", blockTransferFailTimeout.currentValue());

		return fs;
	}

	public void setOutputLimit(int obwLimit) {
		requestOutputThrottle.changeNanosAndBucketSize((int)((1000L*1000L*1000L) / (obwLimit)), Math.max(obwLimit*60, 32768*20));
		if(node.inputLimitDefault) {
			setInputLimit(obwLimit * 4);
		}
	}

	public void setInputLimit(int ibwLimit) {
		requestInputThrottle.changeNanosAndBucketSize((int)((1000L*1000L*1000L) / (ibwLimit)), Math.max(ibwLimit*60, 32768*20));
	}

	public boolean isTestnetEnabled() {
		return node.isTestnetEnabled();
	}

	public boolean getRejectReasonsTable(HTMLNode table) {
		return preemptiveRejectReasons.toTableRows(table) > 0;
	}

	public boolean getLocalRejectReasonsTable(HTMLNode table) {
		return localPreemptiveRejectReasons.toTableRows(table) > 0;
	}

	public synchronized void requestCompleted(boolean succeeded, boolean isRemote, boolean isSSK) {
		globalFetchPSuccess.report(succeeded ? 1.0 : 0.0);
		if(isSSK) {
			if (isRemote) {
				sskRemoteFetchPSuccess.report(succeeded ? 1.0 : 0.0);
			} else {
				sskLocalFetchPSuccess.report(succeeded ? 1.0 : 0.0);
			}
		} else {
			if (isRemote) {
				chkRemoteFetchPSuccess.report(succeeded ? 1.0 : 0.0);
			} else {
				chkLocalFetchPSuccess.report(succeeded ? 1.0 : 0.0);
			}
		}
	}

	private final DecimalFormat fix3p3pct = new DecimalFormat("##0.000%");
	private final NumberFormat thousandPoint = NumberFormat.getInstance();

	public void fillSuccessRateBox(HTMLNode parent) {
		HTMLNode list = parent.addChild("table", "border", "0");
		final RunningAverage[] averages = new RunningAverage[] {
				globalFetchPSuccess,
				chkLocalFetchPSuccess,
				chkRemoteFetchPSuccess,
				sskLocalFetchPSuccess,
				sskRemoteFetchPSuccess,
				blockTransferPSuccess,
				blockTransferFailTurtled,
				blockTransferFailTimeout
		};
		final String[] names = new String[] {
				l10n("allRequests"),
				l10n("localCHKs"),
				l10n("remoteCHKs"),
				l10n("localSSKs"),
				l10n("remoteSSKs"),
				l10n("blockTransfers"),
				l10n("turtledDownstream"),
				l10n("transfersTimedOut")
		};
		HTMLNode row = list.addChild("tr");
		row.addChild("th", l10n("group"));
		row.addChild("th", l10n("pSuccess"));
		row.addChild("th", l10n("count"));

		for(int i=0;i<averages.length;i++) {
			row = list.addChild("tr");
			row.addChild("td", names[i]);
			if (averages[i].countReports()==0) {
				row.addChild("td", "-");
				row.addChild("td", "0");
			} else {
				row.addChild("td", fix3p3pct.format(averages[i].currentValue()));
				row.addChild("td", thousandPoint.format(averages[i].countReports()));
			}
		}

		row = list.addChild("tr");
		row.addChild("td", l10n("turtleRequests"));
		long total;
		long succeeded;
		synchronized(this) {
			total = turtleTransfersCompleted;
			succeeded = turtleSuccesses;
		}
		if(total == 0) {
			row.addChild("td", "-");
			row.addChild("td", "0");
		} else {
			row.addChild("td", fix3p3pct.format((double)succeeded / total));
			row.addChild("td", thousandPoint.format(total));
		}
	}

	/* Total bytes sent by requests and inserts, excluding payload */
	private long chkRequestSentBytes;
	private long chkRequestRcvdBytes;
	private long sskRequestSentBytes;
	private long sskRequestRcvdBytes;
	private long chkInsertSentBytes;
	private long chkInsertRcvdBytes;
	private long sskInsertSentBytes;
	private long sskInsertRcvdBytes;

	public synchronized void requestSentBytes(boolean ssk, int x) {
		if(ssk)
			sskRequestSentBytes += x;
		else
			chkRequestSentBytes += x;
	}

	public synchronized void requestReceivedBytes(boolean ssk, int x) {
		if(ssk)
			sskRequestRcvdBytes += x;
		else
			chkRequestRcvdBytes += x;
	}

	public synchronized void insertSentBytes(boolean ssk, int x) {
		if(logDEBUG)
			Logger.debug(this, "insertSentBytes("+ssk+", "+x+")");
		if(ssk)
			sskInsertSentBytes += x;
		else
			chkInsertSentBytes += x;
	}

	public synchronized void insertReceivedBytes(boolean ssk, int x) {
		if(ssk)
			sskInsertRcvdBytes += x;
		else
			chkInsertRcvdBytes += x;
	}

	public synchronized long getCHKRequestTotalBytesSent() {
		return chkRequestSentBytes;
	}

	public synchronized long getSSKRequestTotalBytesSent() {
		return sskRequestSentBytes;
	}

	public synchronized long getCHKInsertTotalBytesSent() {
		return chkInsertSentBytes;
	}

	public synchronized long getSSKInsertTotalBytesSent() {
		return sskInsertSentBytes;
	}

	private long offeredKeysSenderRcvdBytes;
	private long offeredKeysSenderSentBytes;

	public synchronized void offeredKeysSenderReceivedBytes(int x) {
		offeredKeysSenderRcvdBytes += x;
	}

	/**
	 * @return The number of bytes sent in replying to FNPGetOfferedKey's.
	 */
	public synchronized void offeredKeysSenderSentBytes(int x) {
		offeredKeysSenderSentBytes += x;
	}

	public long getOfferedKeysTotalBytesReceived() {
		return offeredKeysSenderRcvdBytes;
	}

	public long getOfferedKeysTotalBytesSent() {
		return offeredKeysSenderSentBytes;
	}

	private long offerKeysRcvdBytes;
	private long offerKeysSentBytes;

	ByteCounter sendOffersCtr = new ByteCounter() {

		public void receivedBytes(int x) {
			synchronized(NodeStats.this) {
				offerKeysRcvdBytes += x;
			}
		}

		public void sentBytes(int x) {
			synchronized(NodeStats.this) {
				offerKeysSentBytes += x;
			}
		}

		public void sentPayload(int x) {
			// Ignore
		}

	};

	public synchronized long getOffersSentBytesSent() {
		return offerKeysSentBytes;
	}

	private long swappingRcvdBytes;
	private long swappingSentBytes;

	public synchronized void swappingReceivedBytes(int x) {
		swappingRcvdBytes += x;
	}

	public synchronized void swappingSentBytes(int x) {
		swappingSentBytes += x;
	}

	public synchronized long getSwappingTotalBytesReceived() {
		return swappingRcvdBytes;
	}

	public synchronized long getSwappingTotalBytesSent() {
		return swappingSentBytes;
	}

	private long totalAuthBytesSent;

	public synchronized void reportAuthBytes(int x) {
		totalAuthBytesSent += x;
	}

	public synchronized long getTotalAuthBytesSent() {
		return totalAuthBytesSent;
	}

	private long resendBytesSent;

	public final ByteCounter resendByteCounter = new ByteCounter() {

		public void receivedBytes(int x) {
			// Ignore
		}

		public void sentBytes(int x) {
			synchronized(NodeStats.this) {
				resendBytesSent += x;
			}
		}

		public void sentPayload(int x) {
			Logger.error(this, "Payload sent in resendByteCounter????", new Exception("error"));
		}

	};

	public synchronized long getResendBytesSent() {
		return resendBytesSent;
	}

	private long uomBytesSent;

	public synchronized void reportUOMBytesSent(int x) {
		uomBytesSent += x;
	}

	public synchronized long getUOMBytesSent() {
		return uomBytesSent;
	}

	// Opennet-related bytes - *not* including bytes sent on requests, those are accounted towards
	// the requests' totals.

	private long announceBytesSent;
	private long announceBytesPayload;

	public final ByteCounter announceByteCounter = new ByteCounter() {

		public void receivedBytes(int x) {
			// Ignore
		}

		public void sentBytes(int x) {
			synchronized(NodeStats.this) {
				announceBytesSent += x;
			}
		}

		public void sentPayload(int x) {
			synchronized(NodeStats.this) {
				announceBytesPayload += x;
			}
		}

	};

	public synchronized long getAnnounceBytesSent() {
		return announceBytesSent;
	}

	public synchronized long getAnnounceBytesPayloadSent() {
		return announceBytesPayload;
	}

	private long routingStatusBytesSent;

	ByteCounter setRoutingStatusCtr = new ByteCounter() {

		public void receivedBytes(int x) {
			// Impossible?
			Logger.error(this, "Routing status sender received bytes: "+x+" - isn't that impossible?");
		}

		public void sentBytes(int x) {
			synchronized(NodeStats.this) {
				routingStatusBytesSent += x;
			}
		}

		public void sentPayload(int x) {
			// Ignore
		}

	};

	public synchronized long getRoutingStatusBytes() {
		return routingStatusBytesSent;
	}

	private long networkColoringReceivedBytesCounter;
	private long networkColoringSentBytesCounter;

	public synchronized void networkColoringReceivedBytes(int x) {
		networkColoringReceivedBytesCounter += x;
	}

	public synchronized void networkColoringSentBytes(int x) {
		networkColoringSentBytesCounter += x;
	}

	public synchronized long getNetworkColoringSentBytes() {
		return networkColoringSentBytesCounter;
	}

	private long pingBytesReceived;
	private long pingBytesSent;

	public synchronized void pingCounterReceived(int x) {
		pingBytesReceived += x;
	}

	public synchronized void pingCounterSent(int x) {
		pingBytesSent += x;
	}

	public synchronized long getPingSentBytes() {
		return pingBytesSent;
	}

	public ByteCounter sskRequestCtr = new ByteCounter() {

		public void receivedBytes(int x) {
			synchronized(NodeStats.this) {
				sskRequestRcvdBytes += x;
			}
		}

		public void sentBytes(int x) {
			synchronized(NodeStats.this) {
				sskRequestSentBytes += x;
			}
		}

		public void sentPayload(int x) {
			// Ignore
		}

	};

	public ByteCounter chkRequestCtr = new ByteCounter() {

		public void receivedBytes(int x) {
			synchronized(NodeStats.this) {
				chkRequestRcvdBytes += x;
			}
		}

		public void sentBytes(int x) {
			synchronized(NodeStats.this) {
				chkRequestSentBytes += x;
			}
		}

		public void sentPayload(int x) {
			// Ignore
		}

	};

	public ByteCounter sskInsertCtr = new ByteCounter() {

		public void receivedBytes(int x) {
			synchronized(NodeStats.this) {
				sskInsertRcvdBytes += x;
			}
		}

		public void sentBytes(int x) {
			synchronized(NodeStats.this) {
				sskInsertSentBytes += x;
			}
		}

		public void sentPayload(int x) {
			// Ignore
		}

	};

	public ByteCounter chkInsertCtr = new ByteCounter() {

		public void receivedBytes(int x) {
			synchronized(NodeStats.this) {
				chkInsertRcvdBytes += x;
			}
		}

		public void sentBytes(int x) {
			synchronized(NodeStats.this) {
				chkInsertSentBytes += x;
			}
		}

		public void sentPayload(int x) {
			// Ignore
		}

	};

	private long probeRequestSentBytes;
	private long probeRequestRcvdBytes;

	public ByteCounter probeRequestCtr = new ByteCounter() {

		public void receivedBytes(int x) {
			synchronized(NodeStats.this) {
				probeRequestRcvdBytes += x;
			}
		}

		public void sentBytes(int x) {
			synchronized(NodeStats.this) {
				probeRequestSentBytes += x;
			}
		}

		public void sentPayload(int x) {
			// Ignore
		}

	};

	public synchronized long getProbeRequestSentBytes() {
		return probeRequestSentBytes;
	}

	private long routedMessageBytesRcvd;
	private long routedMessageBytesSent;

	public ByteCounter routedMessageCtr = new ByteCounter() {

		public void receivedBytes(int x) {
			synchronized(NodeStats.this) {
				routedMessageBytesRcvd += x;
			}
		}

		public void sentBytes(int x) {
			synchronized(NodeStats.this) {
				routedMessageBytesSent += x;
			}
		}

		public void sentPayload(int x) {
			// Ignore
		}

	};

	public synchronized long getRoutedMessageSentBytes() {
		return routedMessageBytesSent;
	}

	private long disconnBytesReceived;
	private long disconnBytesSent;

	void disconnBytesReceived(int x) {
		this.disconnBytesReceived += x;
	}

	void disconnBytesSent(int x) {
		this.disconnBytesSent += x;
	}

	public long getDisconnBytesSent() {
		return disconnBytesSent;
	}

	private long initialMessagesBytesReceived;
	private long initialMessagesBytesSent;

	ByteCounter initialMessagesCtr = new ByteCounter() {

		public void receivedBytes(int x) {
			synchronized(NodeStats.this) {
				initialMessagesBytesReceived += x;
			}
		}

		public void sentBytes(int x) {
			synchronized(NodeStats.this) {
				initialMessagesBytesSent += x;
			}
		}

		public void sentPayload(int x) {
			// Ignore
		}

	};

	public synchronized long getInitialMessagesBytesSent() {
		return initialMessagesBytesSent;
	}

	private long changedIPBytesReceived;
	private long changedIPBytesSent;

	ByteCounter changedIPCtr = new ByteCounter() {

		public void receivedBytes(int x) {
			synchronized(NodeStats.this) {
				changedIPBytesReceived += x;
			}
		}

		public void sentBytes(int x) {
			synchronized(NodeStats.this) {
				changedIPBytesSent += x;
			}
		}

		public void sentPayload(int x) {
			// Ignore
		}

	};

	public long getChangedIPBytesSent() {
		return changedIPBytesSent;
	}

	private long nodeToNodeRcvdBytes;
	private long nodeToNodeSentBytes;

	final ByteCounter nodeToNodeCounter = new ByteCounter() {

		public void receivedBytes(int x) {
			synchronized(NodeStats.this) {
				nodeToNodeRcvdBytes += x;
			}
		}

		public void sentBytes(int x) {
			synchronized(NodeStats.this) {
				nodeToNodeSentBytes += x;
			}
		}

		public void sentPayload(int x) {
			// Ignore
		}

	};

	public long getNodeToNodeBytesSent() {
		return nodeToNodeSentBytes;
	}
	
	private long allocationNoticesCounterBytesReceived;
	private long allocationNoticesCounterBytesSent;
	
	final ByteCounter allocationNoticesCounter = new ByteCounter() {
		
		public void receivedBytes(int x) {
			synchronized(NodeStats.this) {
				allocationNoticesCounterBytesReceived += x;
			}
		}

		public void sentBytes(int x) {
			synchronized(NodeStats.this) {
				allocationNoticesCounterBytesSent += x;
			}
		}

		public void sentPayload(int x) {
			// Ignore
		}
		
	};
	
	public long getAllocationNoticesBytesSent() {
		return allocationNoticesCounterBytesSent;
	}

	

	private long notificationOnlySentBytes;

	synchronized void reportNotificationOnlyPacketSent(int packetSize) {
		notificationOnlySentBytes += packetSize;
	}

	public long getNotificationOnlyPacketsSentBytes() {
		return notificationOnlySentBytes;
	}

	public synchronized long getSentOverhead() {
		return offerKeysSentBytes // offers we have sent
		+ swappingSentBytes // swapping
		+ totalAuthBytesSent // connection setup
		+ resendBytesSent // resends - FIXME might be dependant on requests?
		+ uomBytesSent // update over mandatory
		+ announceBytesSent // announcements, including payload
		+ routingStatusBytesSent // routing status
		+ networkColoringSentBytesCounter // network coloring
		+ pingBytesSent // ping bytes
		+ probeRequestSentBytes // probe requests
		+ routedMessageBytesSent // routed test messages
		+ disconnBytesSent // disconnection related bytes
		+ initialMessagesBytesSent // initial messages
		+ changedIPBytesSent // changed IP
		+ nodeToNodeSentBytes // n2n messages
		+ notificationOnlySentBytes; // ack-only packets
	}

	/**
	 * The average number of bytes sent per second for things other than requests, inserts,
	 * and offer replies.
	 */
	public double getSentOverheadPerSecond() {
		long uptime = node.getUptime();
		return (getSentOverhead() * 1000.0) / uptime;
	}

	public synchronized void successfulBlockReceive() {
		blockTransferPSuccess.report(1.0);
		if(logMINOR) Logger.minor(this, "Successful receives: "+blockTransferPSuccess.currentValue()+" count="+blockTransferPSuccess.countReports());
	}

	public synchronized void failedBlockReceive(boolean normalFetch, boolean timeout, boolean turtle) {
		if(normalFetch) {
			blockTransferFailTurtled.report(turtle ? 1.0 : 0.0);
			blockTransferFailTimeout.report(timeout ? 1.0 : 0.0);
		}
		blockTransferPSuccess.report(0.0);
		if(logMINOR) Logger.minor(this, "Successful receives: "+blockTransferPSuccess.currentValue()+" count="+blockTransferPSuccess.countReports());
	}

	public void reportIncomingRequestLocation(double loc) {
		assert((loc > 0) && (loc < 1.0));

		synchronized(incomingRequestsByLoc) {
			incomingRequestsByLoc[(int)Math.floor(loc*incomingRequestsByLoc.length)]++;
			incomingRequestsAccounted++;
		}
	}

	public int[] getIncomingRequestLocation(int[] retval) {
		int[] result = new int[incomingRequestsByLoc.length];
		synchronized(incomingRequestsByLoc) {
			System.arraycopy(incomingRequestsByLoc, 0, result, 0, incomingRequestsByLoc.length);
			retval[0] = incomingRequestsAccounted;
		}

		return result;
	}

	public void reportOutgoingLocalRequestLocation(double loc) {
		assert((loc > 0) && (loc < 1.0));

		synchronized(outgoingLocalRequestByLoc) {
			outgoingLocalRequestByLoc[(int)Math.floor(loc*outgoingLocalRequestByLoc.length)]++;
			outgoingLocalRequestsAccounted++;
		}
	}

	public int[] getOutgoingLocalRequestLocation(int[] retval) {
		int[] result = new int[outgoingLocalRequestByLoc.length];
		synchronized(outgoingLocalRequestByLoc) {
			System.arraycopy(outgoingLocalRequestByLoc, 0, result, 0, outgoingLocalRequestByLoc.length);
			retval[0] = outgoingLocalRequestsAccounted;
		}

		return result;
	}

	public void reportOutgoingRequestLocation(double loc) {
		assert((loc > 0) && (loc < 1.0));

		synchronized(outgoingRequestByLoc) {
			outgoingRequestByLoc[(int)Math.floor(loc*outgoingRequestByLoc.length)]++;
			outgoingRequestsAccounted++;
		}
	}

	public int[] getOutgoingRequestLocation(int[] retval) {
		int[] result = new int[outgoingRequestByLoc.length];
		synchronized(outgoingRequestByLoc) {
			System.arraycopy(outgoingRequestByLoc, 0, result, 0, outgoingRequestByLoc.length);
			retval[0] = outgoingRequestsAccounted;
		}

		return result;
	}

	public void reportCHKTime(long rtt, boolean successful) {
		if(successful)
			successfulLocalCHKFetchTimeAverage.report(rtt);
		else
			unsuccessfulLocalCHKFetchTimeAverage.report(rtt);
		localCHKFetchTimeAverage.report(rtt);
	}

	public void fillDetailedTimingsBox(HTMLNode html) {
		HTMLNode table = html.addChild("table");
		HTMLNode row = table.addChild("tr");
		row.addChild("td", "Successful");
		row.addChild("td", TimeUtil.formatTime((long)successfulLocalCHKFetchTimeAverage.currentValue(), 2, true));
		row = table.addChild("tr");
		row.addChild("td", "Unsuccessful");
		row.addChild("td", TimeUtil.formatTime((long)unsuccessfulLocalCHKFetchTimeAverage.currentValue(), 2, true));
		row = table.addChild("tr");
		row.addChild("td", "Average");
		row.addChild("td", TimeUtil.formatTime((long)localCHKFetchTimeAverage.currentValue(), 2, true));
	}

	private long turtleTransfersCompleted;
	private long turtleSuccesses;

	synchronized void turtleSucceeded() {
		turtleSuccesses++;
		turtleTransfersCompleted++;
	}

	synchronized void turtleFailed() {
		turtleTransfersCompleted++;
	}

	private HourlyStats hourlyStats;

	void remoteRequest(boolean ssk, boolean success, boolean local, short htl, double location) {
		hourlyStats.remoteRequest(ssk, success, local, htl, location);
	}

	public void fillRemoteRequestHTLsBox(HTMLNode html) {
		hourlyStats.fillRemoteRequestHTLsBox(html);
	}

	private String sanitizeDBJobType(String jobType) {
		int typeBeginIndex = jobType.lastIndexOf('.'); // Only use the actual class name, exclude the packages
		int typeEndIndex = jobType.indexOf('@');

		if(typeBeginIndex < 0)
			typeBeginIndex = jobType.lastIndexOf(':'); // Strip "DBJobWrapper:" prefix

		if(typeBeginIndex < 0)
			typeBeginIndex = 0;
		else
			++typeBeginIndex;

		if(typeEndIndex < 0)
			typeEndIndex = jobType.length();

		return jobType.substring(typeBeginIndex, typeEndIndex);
	}

	public void reportDatabaseJob(String jobType, long executionTimeMiliSeconds) {
		jobType = sanitizeDBJobType(jobType);

		TrivialRunningAverage avg;

		synchronized(avgDatabaseJobExecutionTimes) {
			avg = avgDatabaseJobExecutionTimes.get(jobType);

			if(avg == null) {
				avg = new TrivialRunningAverage();
				avgDatabaseJobExecutionTimes.put(jobType, avg);
			}
		}

		avg.report(executionTimeMiliSeconds);
	}

	/**
	 * View of stats for CHK Store
	 *
	 * @return stats for CHK Store
	 */
	public NodeStoreStats chkStoreStats() {
		return new NodeStoreStats() {
			public double avgLocation() {
				return avgStoreCHKLocation.currentValue();
			}

			public double avgSuccess() {
				return avgStoreCHKSuccess.currentValue();
			}

			public double furthestSuccess() throws StatsNotAvailableException {
				return furthestStoreCHKSuccess;
			}

			public double avgDist() throws StatsNotAvailableException {
				return Location.distance(nodeLoc, avgLocation());
			}

			public double distanceStats() throws StatsNotAvailableException {
				return cappedDistance(avgStoreCHKLocation, node.getChkDatastore());
			}
		};
	}

	/**
	 * View of stats for CHK Cache
	 *
	 * @return CHK cache stats
	 */
	public NodeStoreStats chkCacheStats() {
		return new NodeStoreStats() {
			public double avgLocation() {
				return avgCacheCHKLocation.currentValue();
			}

			public double avgSuccess() {
				return avgCacheCHKSuccess.currentValue();
			}

			public double furthestSuccess() throws StatsNotAvailableException {
				return furthestCacheCHKSuccess;
			}

			public double avgDist() throws StatsNotAvailableException {
				return Location.distance(nodeLoc, avgLocation());
			}

			public double distanceStats() throws StatsNotAvailableException {
				return cappedDistance(avgCacheCHKLocation, node.getChkDatacache());
			}
		};
	}

	/**
	 * View of stats for CHK SlashdotCache
	 *
	 * @return CHK Slashdotcache stats
	 */
	public NodeStoreStats chkSlashDotCacheStats() {
		return new NodeStoreStats() {
			public double avgLocation() {
				return avgSlashdotCacheCHKLocation.currentValue();
			}

			public double avgSuccess() {
				return avgSlashdotCacheCHKSucess.currentValue();
			}

			public double furthestSuccess() throws StatsNotAvailableException {
				return furthestSlashdotCacheCHKSuccess;
			}

			public double avgDist() throws StatsNotAvailableException {
				return Location.distance(nodeLoc, avgLocation());
			}

			public double distanceStats() throws StatsNotAvailableException {
				return cappedDistance(avgSlashdotCacheCHKLocation, node.getChkDatacache());
			}
		};
	}

		/**
	 * View of stats for CHK ClientCache
	 *
	 * @return CHK ClientCache stats
	 */
	public NodeStoreStats chkClientCacheStats() {
		return new NodeStoreStats() {
			public double avgLocation() {
				return avgClientCacheCHKLocation.currentValue();
			}

			public double avgSuccess() {
				return avgClientCacheCHKSuccess.currentValue();
			}

			public double furthestSuccess() throws StatsNotAvailableException {
				return furthestClientCacheCHKSuccess;
			}

			public double avgDist() throws StatsNotAvailableException {
				return Location.distance(nodeLoc, avgLocation());
			}

			public double distanceStats() throws StatsNotAvailableException {
				return cappedDistance(avgClientCacheCHKLocation, node.getChkDatacache());
			}
		};
	}

	/**
	 * View of stats for SSK Store
	 *
	 * @return stats for SSK Store
	 */
	public NodeStoreStats sskStoreStats() {
		return new NodeStoreStats() {
			public double avgLocation() {
				return avgStoreSSKLocation.currentValue();
			}

			public double avgSuccess() {
				return avgStoreSSKSuccess.currentValue();
			}

			public double furthestSuccess() throws StatsNotAvailableException {
				return furthestStoreSSKSuccess;
			}

			public double avgDist() throws StatsNotAvailableException {
				return Location.distance(nodeLoc, avgLocation());
			}

			public double distanceStats() throws StatsNotAvailableException {
				return cappedDistance(avgStoreSSKLocation, node.getChkDatastore());
			}
		};
	}

	/**
	 * View of stats for SSK Cache
	 *
	 * @return SSK cache stats
	 */
	public NodeStoreStats sskCacheStats() {
		return new NodeStoreStats() {
			public double avgLocation() {
				return avgCacheSSKLocation.currentValue();
			}

			public double avgSuccess() {
				return avgCacheSSKSuccess.currentValue();
			}

			public double furthestSuccess() throws StatsNotAvailableException {
				return furthestCacheSSKSuccess;
			}

			public double avgDist() throws StatsNotAvailableException {
				return Location.distance(nodeLoc, avgLocation());
			}

			public double distanceStats() throws StatsNotAvailableException {
				return cappedDistance(avgCacheSSKLocation, node.getChkDatacache());
			}
		};
	}

	/**
	 * View of stats for SSK SlashdotCache
	 *
	 * @return SSK Slashdotcache stats
	 */
	public NodeStoreStats sskSlashDotCacheStats() {
		return new NodeStoreStats() {
			public double avgLocation() {
				return avgSlashdotCacheSSKLocation.currentValue();
			}

			public double avgSuccess() {
				return avgSlashdotCacheSSKSuccess.currentValue();
			}

			public double furthestSuccess() throws StatsNotAvailableException {
				return furthestSlashdotCacheSSKSuccess;
			}

			public double avgDist() throws StatsNotAvailableException {
				return Location.distance(nodeLoc, avgLocation());
			}

			public double distanceStats() throws StatsNotAvailableException {
				return cappedDistance(avgSlashdotCacheSSKLocation, node.getChkDatacache());
			}
		};
	}

		/**
	 * View of stats for SSK ClientCache
	 *
	 * @return SSK ClientCache stats
	 */
	public NodeStoreStats sskClientCacheStats() {
		return new NodeStoreStats() {
			public double avgLocation() {
				return avgClientCacheSSKLocation.currentValue();
			}

			public double avgSuccess() {
				return avgClientCacheSSKSuccess.currentValue();
			}

			public double furthestSuccess() throws StatsNotAvailableException {
				return furthestClientCacheSSKSuccess;
			}

			public double avgDist() throws StatsNotAvailableException {
				return Location.distance(nodeLoc, avgLocation());
			}

			public double distanceStats() throws StatsNotAvailableException {
				return cappedDistance(avgClientCacheSSKLocation, node.getChkDatacache());
			}
		};
	}




	private double cappedDistance(DecayingKeyspaceAverage avgLocation, CHKStore store) {
		double cachePercent = 1.0 * avgLocation.countReports() / store.keyCount();
		//Cap the reported value at 100%, as the decaying average does not account beyond that anyway.
		if (cachePercent > 1.0) {
			cachePercent = 1.0;
		}
		return cachePercent;
	}


	public static class DatabaseJobStats implements Comparable<DatabaseJobStats> {
		public final String jobType;
		public final long count;
		public final long avgTime;
		public final long totalTime;

		public DatabaseJobStats(String myJobType, long myCount, long myAvgTime, long myTotalTime) {
			jobType = myJobType;
			count = myCount;
			avgTime = myAvgTime;
			totalTime = myTotalTime;
		}

		public int compareTo(DatabaseJobStats o) {
			if(avgTime < o.avgTime)
				return 1;
			else if(avgTime == o.avgTime)
				return 0;
			else
				return -1;
		}
	}

	public DatabaseJobStats[] getDatabaseJobExecutionStatistics() {
		DatabaseJobStats[] entries = new DatabaseJobStats[avgDatabaseJobExecutionTimes.size()];
		int i = 0;

		synchronized(avgDatabaseJobExecutionTimes) {
			for(Map.Entry<String, TrivialRunningAverage> entry : avgDatabaseJobExecutionTimes.entrySet()) {
				TrivialRunningAverage avg = entry.getValue();
				entries[i++] = new DatabaseJobStats(entry.getKey(), avg.countReports(), (long)avg.currentValue(), (long)avg.totalValue());
			}
		}

		Arrays.sort(entries);
		return entries;
	}

	public StringCounter getDatabaseJobQueueStatistics() {
		final StringCounter result = new StringCounter();

		final LinkedList<Runnable>[] dbJobs = node.clientCore.clientDatabaseExecutor.getQueuedJobsByPriority();

		for(LinkedList<Runnable> list : dbJobs) {
			for(Runnable job : list) {
				result.inc(sanitizeDBJobType(job.toString()));
			}
		}

		return result;
	}

	public PeerLoadStats createPeerLoadStats(PeerNode peer, boolean realTimeFlag) {
		return new PeerLoadStats(peer, realTimeFlag);
	}

	public PeerLoadStats parseLoadStats(PeerNode source, Message m) {
		return new PeerLoadStats(source, m);
	}

	public ByteCountersSnapshot getByteCounters(boolean input) {
		return new ByteCountersSnapshot(input);
	}

	public RunningRequestsSnapshot getRunningRequestsTo(PeerNode peerNode, boolean realTimeFlag) {
		return new RunningRequestsSnapshot(node, peerNode, true, realTimeFlag, ignoreLocalVsRemoteBandwidthLiability, outwardTransfersPerInsert());
	}
	
	public boolean ignoreLocalVsRemoteBandwidthLiability() {
		return ignoreLocalVsRemoteBandwidthLiability;
	}
	
	private int totalAnnouncements;
	private int totalAnnounceForwards;
	
	public synchronized void reportAnnounceForwarded(int forwardedRefs) {
		totalAnnouncements++;
		totalAnnounceForwards += forwardedRefs;
		if(logMINOR) Logger.minor(this, "Announcements: "+totalAnnouncements+" average "+((totalAnnounceForwards*1.0)/totalAnnouncements));
		// FIXME add to stats page
	}
	
	public synchronized int getTransfersPerAnnounce() {
		if(totalAnnouncements == 0) return 1;
		return (int)Math.max(1, Math.ceil((totalAnnounceForwards*1.0)/totalAnnouncements));
	}

	private final HashSet<Long> runningAnnouncements = new HashSet<Long>();

	// FIXME make configurable, more sophisticated.
	
	/** To prevent thread overflow */
	private final static int MAX_ANNOUNCEMENTS = 100;
	
	public boolean shouldAcceptAnnouncement(long uid) {
		int outputPerSecond = node.getOutputBandwidthLimit() / 2; // FIXME: Take overhead into account??? Be careful, it may include announcements and that would cause problems!
		int inputPerSecond = node.getInputBandwidthLimit() / 2;
		int limit = Math.min(inputPerSecond, outputPerSecond);
		synchronized(this) {
			int transfersPerAnnouncement = getTransfersPerAnnounce();
			int running = runningAnnouncements.size();
			if(running >= MAX_ANNOUNCEMENTS) return false;
			// Liability-style limiting as well.
			int perTransfer = OpennetManager.PADDED_NODEREF_SIZE;
			// Must all complete in 30 seconds. That is the timeout for one block.
			int bandwidthIn30Secs = limit * 30;
			if(perTransfer * transfersPerAnnouncement * running > bandwidthIn30Secs) 
				return false;
			runningAnnouncements.add(uid);
			return true;
		}
	}
	
	public synchronized void endAnnouncement(long uid) {
		runningAnnouncements.remove(uid);
	}
}
