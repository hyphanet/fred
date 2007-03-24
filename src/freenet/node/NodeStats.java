package freenet.node;

import java.io.File;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.Comparator;

import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.crypt.RandomSource;
import freenet.io.comm.DMT;
import freenet.io.comm.IOStatisticCollector;
import freenet.node.Node.NodeInitException;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.SizeUtil;
import freenet.support.TimeUtil;
import freenet.support.TokenBucket;
import freenet.support.api.BooleanCallback;
import freenet.support.api.IntCallback;
import freenet.support.math.RunningAverage;
import freenet.support.math.TimeDecayingRunningAverage;

/** Node (as opposed to NodeClientCore) level statistics. Includes shouldRejectRequest(), but not limited
 * to stuff required to implement that. */
public class NodeStats implements Persistable {

	/** Minimum free heap memory bytes required to accept a request (perhaps fewer OOMs this way) */
	public static final long MIN_FREE_HEAP_BYTES_FOR_ROUTING_SUCCESS = 3L * 1024 * 1024;  // 3 MiB
	
	/** Minimum free heap memory percentage required to accept a request (perhaps fewer OOMs this way) */
	public static final double MIN_FREE_HEAP_PERCENT_FOR_ROUTING_SUCCESS = 0.01;  // 1%
	
	/** Sub-max ping time. If ping is greater than this, we reject some requests. */
	public static final long SUB_MAX_PING_TIME = 700;
	/** Maximum overall average ping time. If ping is greater than this,
	 * we reject all requests. */
	public static final long MAX_PING_TIME = 1500;
	/** Maximum throttled packet delay. If the throttled packet delay is greater
	 * than this, reject all packets. */
	public static final long MAX_THROTTLE_DELAY = 3000;
	/** If the throttled packet delay is less than this, reject no packets; if it's
	 * between the two, reject some packets. */
	public static final long SUB_MAX_THROTTLE_DELAY = 2000;
	/** How high can bwlimitDelayTime be before we alert (in milliseconds)*/
	public static final long MAX_BWLIMIT_DELAY_TIME_ALERT_THRESHOLD = MAX_THROTTLE_DELAY*2;
	/** How high can nodeAveragePingTime be before we alert (in milliseconds)*/
	public static final long MAX_NODE_AVERAGE_PING_TIME_ALERT_THRESHOLD = MAX_PING_TIME*2;
	/** How long we're over the bwlimitDelayTime threshold before we alert (in milliseconds)*/
	public static final long MAX_BWLIMIT_DELAY_TIME_ALERT_DELAY = 10*60*1000;  // 10 minutes
	/** How long we're over the nodeAveragePingTime threshold before we alert (in milliseconds)*/
	public static final long MAX_NODE_AVERAGE_PING_TIME_ALERT_DELAY = 10*60*1000;  // 10 minutes
	/** Accept one request every 10 seconds regardless, to ensure we update the
	 * block send time.
	 */
	public static final int MAX_INTERREQUEST_TIME = 10*1000;

	private final Node node;
	private Thread myMemoryCheckerThread;
	private MemoryChecker myMemoryChecker;
	public final PeerManager peers;
	
	final RandomSource hardRandom;
	
	private boolean logMINOR;
	
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

	/** Average delay caused by throttling for sending a packet */
	final TimeDecayingRunningAverage throttledPacketSendAverage;
	
	// Stats
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

	File persistTarget; 
	File persistTemp;
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
	public RunningAverage routingMissDistance = new TimeDecayingRunningAverage(0.0, 180000, 0.0, 1.0);
	public RunningAverage backedOffPercent = new TimeDecayingRunningAverage(0.0, 180000, 0.0, 1.0);
	protected final Persister persister;
	
	// ThreadCounting stuffs
	private final ThreadGroup rootThreadGroup;
	private int threadLimit;
	
	final NodePinger nodePinger;

	// Peers stats
	/** Next time to update PeerManagerUserAlert stats */
	private long nextPeerManagerUserAlertStatsUpdateTime = -1;
	/** PeerManagerUserAlert stats update interval (milliseconds) */
	private static final long peerManagerUserAlertStatsUpdateInterval = 1000;  // 1 second
	
	NodeStats(Node node, int sortOrder, SubConfig statsConfig, SimpleFieldSet oldThrottleFS, int obwLimit, int ibwLimit) throws NodeInitException {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		this.node = node;
		this.peers = node.peers;
		this.hardRandom = node.random;
		pInstantRejectIncoming = new TimeDecayingRunningAverage(0, 60000, 0.0, 1.0);
		ThreadGroup tg = Thread.currentThread().getThreadGroup();
		while(tg.getParent() != null) tg = tg.getParent();
		this.rootThreadGroup = tg;
		throttledPacketSendAverage =
			new TimeDecayingRunningAverage(1, 10*60*1000 /* should be significantly longer than a typical transfer */, 0, Long.MAX_VALUE);
		nodePinger = new NodePinger(node);

		previous_input_stat = 0;
		previous_output_stat = 0;
		previous_io_stat_time = 1;
		last_input_stat = 0;
		last_output_stat = 0;
		last_io_stat_time = 3;

		statsConfig.register("threadLimit", 500, sortOrder++, true, true, "Thread limit", "The node will try to limit its thread usage to the specified value, refusing new requests",
				new IntCallback() {
					public int get() {
						return threadLimit;
					}
					public void set(int val) throws InvalidConfigValueException {
						if(val == get()) return;
						if(val < 100)
							throw new InvalidConfigValueException("This value is to low for that setting, increase it!");
						threadLimit = val;
					}
		});
		threadLimit = statsConfig.getInt("threadLimit");
		
		statsConfig.register("memoryChecker", true, sortOrder++, true, false, "Enable the Memory checking thread", "Enable the memory checking thread", 
				new BooleanCallback(){
					public boolean get() {
						return (myMemoryCheckerThread != null);
					}

					public void set(boolean val) throws InvalidConfigValueException {
						if(val == get()) return;
						if(val == false){
							myMemoryChecker.terminate();
							myMemoryChecker = null;
							myMemoryCheckerThread = null;
						} else {
							myMemoryChecker = new MemoryChecker();
							myMemoryCheckerThread = new Thread(myMemoryChecker, "Memory checker");
							myMemoryCheckerThread.setPriority(Thread.MAX_PRIORITY);
							myMemoryCheckerThread.setDaemon(true);
							myMemoryCheckerThread.start();
						}
					}
		});
		
		if(statsConfig.getBoolean("memoryChecker")){
			myMemoryChecker = new MemoryChecker();
			myMemoryCheckerThread = new Thread(myMemoryChecker, "Memory checker");
			myMemoryCheckerThread.setPriority(Thread.MAX_PRIORITY);
			myMemoryCheckerThread.setDaemon(true);
		}

		persister = new ConfigurablePersister(this, statsConfig, "nodeThrottleFile", "node-throttle.dat", sortOrder++, true, false, 
				"File to store node statistics in", "File to store node statistics in (not client statistics, and these are used to decide whether to accept requests so please don't delete)");
		
		SimpleFieldSet throttleFS = persister.read();
		
		if(throttleFS == null)
			throttleFS = oldThrottleFS;

		if(logMINOR) Logger.minor(this, "Read throttleFS:\n"+throttleFS);
		
		// Guesstimates. Hopefully well over the reality.
		localChkFetchBytesSentAverage = new TimeDecayingRunningAverage(500, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("LocalChkFetchBytesSentAverage"));
		localSskFetchBytesSentAverage = new TimeDecayingRunningAverage(500, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("LocalSskFetchBytesSentAverage"));
		localChkInsertBytesSentAverage = new TimeDecayingRunningAverage(32768, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("LocalChkInsertBytesSentAverage"));
		localSskInsertBytesSentAverage = new TimeDecayingRunningAverage(2048, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("LocalSskInsertBytesSentAverage"));
		localChkFetchBytesReceivedAverage = new TimeDecayingRunningAverage(32768, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("LocalChkFetchBytesReceivedAverage"));
		localSskFetchBytesReceivedAverage = new TimeDecayingRunningAverage(2048, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("LocalSskFetchBytesReceivedAverage"));
		localChkInsertBytesReceivedAverage = new TimeDecayingRunningAverage(1024, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("LocalChkInsertBytesReceivedAverage"));
		localSskInsertBytesReceivedAverage = new TimeDecayingRunningAverage(500, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("LocalChkInsertBytesReceivedAverage"));

		remoteChkFetchBytesSentAverage = new TimeDecayingRunningAverage(32768+1024+500, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("RemoteChkFetchBytesSentAverage"));
		remoteSskFetchBytesSentAverage = new TimeDecayingRunningAverage(1024+1024+500, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("RemoteSskFetchBytesSentAverage"));
		remoteChkInsertBytesSentAverage = new TimeDecayingRunningAverage(32768+32768+1024, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("RemoteChkInsertBytesSentAverage"));
		remoteSskInsertBytesSentAverage = new TimeDecayingRunningAverage(1024+1024+500, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("RemoteSskInsertBytesSentAverage"));
		remoteChkFetchBytesReceivedAverage = new TimeDecayingRunningAverage(32768+1024+500, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("RemoteChkFetchBytesReceivedAverage"));
		remoteSskFetchBytesReceivedAverage = new TimeDecayingRunningAverage(2048+500, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("RemoteSskFetchBytesReceivedAverage"));
		remoteChkInsertBytesReceivedAverage = new TimeDecayingRunningAverage(32768+1024+500, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("RemoteChkInsertBytesReceivedAverage"));
		remoteSskInsertBytesReceivedAverage = new TimeDecayingRunningAverage(1024+1024+500, 180000, 0.0, 1024*1024*1024, throttleFS == null ? null : throttleFS.subset("RemoteSskInsertBytesReceivedAverage"));
		
		requestOutputThrottle = 
			new TokenBucket(Math.max(obwLimit*60, 32768*20), (1000L*1000L*1000L) /  obwLimit, 0);
		requestInputThrottle = 
			new TokenBucket(Math.max(ibwLimit*60, 32768*20), (1000L*1000L*1000L) / ibwLimit, 0);
	}
	
	public void start() throws NodeInitException {
		nodePinger.start();
		persister.start();
	}
	
	private long lastAcceptedRequest = -1;
	
	private long lastCheckedUncontended = -1;
	
	static final int ESTIMATED_SIZE_OF_ONE_THROTTLED_PACKET = 
		1024 + DMT.packetTransmitSize(1024, 32)
		+ FNPPacketMangler.HEADERS_LENGTH_ONE_MESSAGE;
	
	/* return reject reason as string if should reject, otherwise return null */
	public String shouldRejectRequest(boolean canAcceptAnyway, boolean isInsert, boolean isSSK) {
		if(logMINOR) dumpByteCostAverages();
		
		if(threadLimit < getActiveThreadCount())
			return "Accepting the request would mean going above the maximum number of allowed threads";
		
		double bwlimitDelayTime = throttledPacketSendAverage.currentValue();
		
		// If no recent reports, no packets have been sent; correct the average downwards.
		long now = System.currentTimeMillis();
		boolean checkUncontended = false;
		synchronized(this) {
			if(now - lastCheckedUncontended > 1000) {
				checkUncontended = true;
				lastCheckedUncontended = now;
			}
		}
		if(checkUncontended && throttledPacketSendAverage.lastReportTime() < now - 5000) {  // if last report more than 5 seconds ago
			// shouldn't take long
			node.outputThrottle.blockingGrab(ESTIMATED_SIZE_OF_ONE_THROTTLED_PACKET);
			node.outputThrottle.recycle(ESTIMATED_SIZE_OF_ONE_THROTTLED_PACKET);
			long after = System.currentTimeMillis();
			// Report time it takes to grab the bytes.
			throttledPacketSendAverage.report(after - now);
			now = after;
			// will have changed, use new value
			synchronized(this) {
				bwlimitDelayTime = throttledPacketSendAverage.currentValue();
			}
		}
		
		double pingTime = nodePinger.averagePingTime();
		synchronized(this) {
			// Round trip time
			if(pingTime > MAX_PING_TIME) {
				if((now - lastAcceptedRequest > MAX_INTERREQUEST_TIME) && canAcceptAnyway) {
					if(logMINOR) Logger.minor(this, "Accepting request anyway (take one every 10 secs to keep bwlimitDelayTime updated)");
				} else {
					pInstantRejectIncoming.report(1.0);
					return ">MAX_PING_TIME ("+TimeUtil.formatTime((long)pingTime, 2, true)+ ')';
				}
			} else if(pingTime > SUB_MAX_PING_TIME) {
				double x = ((double)(pingTime - SUB_MAX_PING_TIME)) / (MAX_PING_TIME - SUB_MAX_PING_TIME);
				if(hardRandom.nextDouble() < x) {
					pInstantRejectIncoming.report(1.0);
					return ">SUB_MAX_PING_TIME ("+TimeUtil.formatTime((long)pingTime, 2, true)+ ')';
				}
			}
		
			// Bandwidth limited packets
			if(bwlimitDelayTime > MAX_THROTTLE_DELAY) {
				if((now - lastAcceptedRequest > MAX_INTERREQUEST_TIME) && canAcceptAnyway) {
					if(logMINOR) Logger.minor(this, "Accepting request anyway (take one every 10 secs to keep bwlimitDelayTime updated)");
				} else {
					pInstantRejectIncoming.report(1.0);
					return ">MAX_THROTTLE_DELAY ("+TimeUtil.formatTime((long)bwlimitDelayTime, 2, true)+ ')';
				}
			} else if(bwlimitDelayTime > SUB_MAX_THROTTLE_DELAY) {
				double x = ((double)(bwlimitDelayTime - SUB_MAX_THROTTLE_DELAY)) / (MAX_THROTTLE_DELAY - SUB_MAX_THROTTLE_DELAY);
				if(hardRandom.nextDouble() < x) {
					pInstantRejectIncoming.report(1.0);
					return ">SUB_MAX_THROTTLE_DELAY ("+TimeUtil.formatTime((long)bwlimitDelayTime, 2, true)+ ')';
				}
			}
			
		}
		
		// Do we have the bandwidth?
		double expected =
			(isInsert ? (isSSK ? this.remoteSskInsertBytesSentAverage : this.remoteChkInsertBytesSentAverage)
					: (isSSK ? this.remoteSskFetchBytesSentAverage : this.remoteChkFetchBytesSentAverage)).currentValue();
		int expectedSent = (int)Math.max(expected, 0);
		if(!requestOutputThrottle.instantGrab(expectedSent)) {
			pInstantRejectIncoming.report(1.0);
			return "Insufficient output bandwidth";
		}
		expected = 
			(isInsert ? (isSSK ? this.remoteSskInsertBytesReceivedAverage : this.remoteChkInsertBytesReceivedAverage)
					: (isSSK ? this.remoteSskFetchBytesReceivedAverage : this.remoteChkFetchBytesReceivedAverage)).currentValue();
		int expectedReceived = (int)Math.max(expected, 0);
		if(!requestInputThrottle.instantGrab(expectedReceived)) {
			requestOutputThrottle.recycle(expectedSent);
			pInstantRejectIncoming.report(1.0);
			return "Insufficient input bandwidth";
		}

		Runtime r = Runtime.getRuntime();
		long maxHeapMemory = r.maxMemory();
		long totalHeapMemory = r.totalMemory();
		long freeHeapMemory = r.freeMemory();
		if(maxHeapMemory < Long.MAX_VALUE) { // would mean unlimited
			freeHeapMemory = maxHeapMemory - (totalHeapMemory - freeHeapMemory);
		}
		if(freeHeapMemory < MIN_FREE_HEAP_BYTES_FOR_ROUTING_SUCCESS) {
			pInstantRejectIncoming.report(1.0);
			return "<MIN_FREE_HEAP_BYTES_FOR_ROUTING_SUCCESS ("+SizeUtil.formatSize(freeHeapMemory, false)+" of "+SizeUtil.formatSize(maxHeapMemory, false)+')';
		}
		double percentFreeHeapMemoryOfMax = ((double) freeHeapMemory) / ((double) maxHeapMemory);
		if(percentFreeHeapMemoryOfMax < MIN_FREE_HEAP_PERCENT_FOR_ROUTING_SUCCESS) {
			pInstantRejectIncoming.report(1.0);
			DecimalFormat fix3p1pct = new DecimalFormat("##0.0%");
			return "<MIN_FREE_HEAP_PERCENT_FOR_ROUTING_SUCCESS ("+SizeUtil.formatSize(freeHeapMemory, false)+" of "+SizeUtil.formatSize(maxHeapMemory, false)+" ("+fix3p1pct.format(percentFreeHeapMemoryOfMax)+"))";
		}

		synchronized(this) {
			if(logMINOR) Logger.minor(this, "Accepting request?");
			lastAcceptedRequest = now;
		}
		
		pInstantRejectIncoming.report(0.0);

		// Accept
		return null;
	}
	
	private void dumpByteCostAverages() {
		Logger.minor(this, "Byte cost averages: REMOTE:"+
				" CHK insert "+remoteChkInsertBytesSentAverage.currentValue()+ '/' +remoteChkInsertBytesReceivedAverage.currentValue()+
				" SSK insert "+remoteSskInsertBytesSentAverage.currentValue()+ '/' +remoteSskInsertBytesReceivedAverage.currentValue()+
				" CHK fetch "+remoteChkFetchBytesSentAverage.currentValue()+ '/' +remoteChkFetchBytesReceivedAverage.currentValue()+
				" SSK fetch "+remoteSskFetchBytesSentAverage.currentValue()+ '/' +remoteSskFetchBytesReceivedAverage.currentValue());
		Logger.minor(this, "Byte cost averages: LOCAL"+
				" CHK insert "+localChkInsertBytesSentAverage.currentValue()+ '/' +localChkInsertBytesReceivedAverage.currentValue()+
				" SSK insert "+localSskInsertBytesSentAverage.currentValue()+ '/' +localSskInsertBytesReceivedAverage.currentValue()+
				" CHK fetch "+localChkFetchBytesSentAverage.currentValue()+ '/' +localChkFetchBytesReceivedAverage.currentValue()+
				" SSK fetch "+localSskFetchBytesSentAverage.currentValue()+ '/' +localSskFetchBytesReceivedAverage.currentValue());
	}

	public double getBwlimitDelayTime() {
		return throttledPacketSendAverage.currentValue();
	}
	
	public double getNodeAveragePingTime() {
		return nodePinger.averagePingTime();
	}

	public int getNetworkSizeEstimate(long timestamp) {
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
			if(getNodeAveragePingTime() > MAX_NODE_AVERAGE_PING_TIME_ALERT_THRESHOLD) {
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
			if(logMINOR && Logger.shouldLog(Logger.DEBUG, this)) Logger.debug(this, "mUPMUAS: "+now+": "+getBwlimitDelayTime()+" >? "+MAX_BWLIMIT_DELAY_TIME_ALERT_THRESHOLD+" since "+firstBwlimitDelayTimeThresholdBreak+" ("+bwlimitDelayAlertRelevant+") "+getNodeAveragePingTime()+" >? "+MAX_NODE_AVERAGE_PING_TIME_ALERT_THRESHOLD+" since "+firstNodeAveragePingTimeThresholdBreak+" ("+nodeAveragePingAlertRelevant+ ')');
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
		return fs;
	}

	/**
	 * Update the node-wide bandwidth I/O stats if the timer has expired
	 */
	public void maybeUpdateNodeIOStats(long now) {
		if(now > nextNodeIOStatsUpdateTime) {
			long[] io_stats = IOStatisticCollector.getTotalIO();
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
		return rootThreadGroup.activeCount();
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
			fs.put("uptimeSeconds", nodeUptimeSeconds);
		}
		fs.put("averagePingTime", getNodeAveragePingTime());
		fs.put("bwlimitDelayTime", getBwlimitDelayTime());
		fs.put("networkSizeEstimateSession", getNetworkSizeEstimate(-1));
		int networkSizeEstimate24hourRecent = getNetworkSizeEstimate(now - (24*60*60*1000));  // 24 hours
		fs.put("networkSizeEstimate24hourRecent", networkSizeEstimate24hourRecent);
		int networkSizeEstimate48hourRecent = getNetworkSizeEstimate(now - (48*60*60*1000));  // 48 hours
		fs.put("networkSizeEstimate48hourRecent", networkSizeEstimate48hourRecent);
		fs.put("routingMissDistance", routingMissDistance.currentValue());
		fs.put("backedOffPercent", backedOffPercent.currentValue());
		fs.put("pInstantReject", pRejectIncomingInstantly());
		fs.put("unclaimedFIFOSize", node.usm.getUnclaimedFIFOSize());
		
		/* gather connection statistics */
		PeerNodeStatus[] peerNodeStatuses = peers.getPeerNodeStatuses();
		Arrays.sort(peerNodeStatuses, new Comparator() {
			public int compare(Object first, Object second) {
				PeerNodeStatus firstNode = (PeerNodeStatus) first;
				PeerNodeStatus secondNode = (PeerNodeStatus) second;
				int statusDifference = firstNode.getStatusValue() - secondNode.getStatusValue();
				if (statusDifference != 0) {
					return statusDifference;
				}
				return firstNode.getName().compareToIgnoreCase(secondNode.getName());
			}
		});
		
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

		fs.put("numberOfInserts", node.getNumInsertSenders());
		fs.put("numberOfRequests", node.getNumRequestSenders());
		fs.put("numberOfTransferringRequests", node.getNumTransferringRequestSenders());
		fs.put("numberOfARKFetchers", node.getNumARKFetchers());

		long[] total = IOStatisticCollector.getTotalIO();
		long total_output_rate = (total[0]) / nodeUptimeSeconds;
		long total_input_rate = (total[1]) / nodeUptimeSeconds;
		long totalPayloadOutput = node.getTotalPayloadSent();
		long total_payload_output_rate = totalPayloadOutput / nodeUptimeSeconds;
		int total_payload_output_percent = (int) (100 * totalPayloadOutput / total[0]);
		fs.put("totalOutputBytes", total[0]);
		fs.put("totalOutputRate", total_output_rate);
		fs.put("totalPayloadOutputBytes", totalPayloadOutput);
		fs.put("totalPayloadOutputRate", total_payload_output_rate);
		fs.put("totalPayloadOutputPercent", total_payload_output_percent);
		fs.put("totalInputBytes", total[1]);
		fs.put("totalInputRate", total_input_rate);
		long[] rate = getNodeIOStats();
		long deltaMS = (rate[5] - rate[2]);
		double recent_output_rate = 1000.0 * (rate[3] - rate[0]) / deltaMS;
		double recent_input_rate = 1000.0 * (rate[4] - rate[1]) / deltaMS;
		fs.put("recentOutputRate", recent_output_rate);
		fs.put("recentInputRate", recent_input_rate);

		String [] routingBackoffReasons = peers.getPeerNodeRoutingBackoffReasons();
		if(routingBackoffReasons.length != 0) {
			for(int i=0;i<routingBackoffReasons.length;i++) {
				fs.put("numberWithRoutingBackoffReasons." + routingBackoffReasons[i], peers.getPeerNodeRoutingBackoffReasonSize(routingBackoffReasons[i]));
			}
		}

		double swaps = (double)node.getSwaps();
		double noSwaps = (double)node.getNoSwaps();
		double numberOfRemotePeerLocationsSeenInSwaps = (double)node.getNumberOfRemotePeerLocationsSeenInSwaps();
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
		int swapsRejectedLoop = node.getSwapsRejectedLoop();
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
			locationChangePerMinute = locationChangePerSession/(double)(nodeUptimeSeconds/60.0);
		}
		if ((swaps > 0.0) && (nodeUptimeSeconds >= 60)) {
			swapsPerMinute = swaps/(double)(nodeUptimeSeconds/60.0);
		}
		if ((noSwaps > 0.0) && (nodeUptimeSeconds >= 60)) {
			noSwapsPerMinute = noSwaps/(double)(nodeUptimeSeconds/60.0);
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
		fs.put("swapsRejectedLoop", swapsRejectedLoop);
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
		float freeMemory = (float) rt.freeMemory();
		float totalMemory = (float) rt.totalMemory();
		float maxMemory = (float) rt.maxMemory();

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
		
		return fs;
	}

	public void setOutputLimit(int obwLimit) {
		obwLimit = (obwLimit * 4) / 5; // fudge factor; take into account non-request activity
		requestOutputThrottle.changeNanosAndBucketSize((1000L*1000L*1000L) /  obwLimit, Math.max(obwLimit*60, 32768*20));
		if(node.inputLimitDefault) {
			int ibwLimit = obwLimit * 4;
			requestInputThrottle.changeNanosAndBucketSize((1000L*1000L*1000L) /  ibwLimit, Math.max(ibwLimit*60, 32768*20));
		}
	}

	public void setInputLimit(int ibwLimit) {
		requestInputThrottle.changeNanosAndBucketSize((1000L*1000L*1000L) /  ibwLimit, Math.max(ibwLimit*60, 32768*20));
	}

	public int getInputLimit() {
		return ((int) ((1000L * 1000L * 1000L) / requestInputThrottle.getNanosPerTick()));
	}

	public boolean isTestnetEnabled() {
		return node.isTestnetEnabled();
	}
	

}
