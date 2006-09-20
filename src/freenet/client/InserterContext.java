package freenet.client;

import freenet.client.async.USKManager;
import freenet.client.events.ClientEventProducer;
import freenet.client.events.SimpleEventProducer;
import freenet.crypt.RandomSource;
import freenet.support.io.BucketFactory;
import freenet.support.io.NullPersistentFileTracker;
import freenet.support.io.PersistentFileTracker;

/** Context object for an insert operation, including both simple and multi-file inserts */
public class InserterContext {

	public final BucketFactory bf;
	public final BucketFactory persistentBucketFactory;
	public final PersistentFileTracker persistentFileTracker;
	/** If true, don't try to compress the data */
	public boolean dontCompress;
	public final RandomSource random;
	public final short splitfileAlgorithm;
	public int maxInsertRetries;
	final int maxSplitInsertThreads;
	public final int consecutiveRNFsCountAsSuccess;
	public final int splitfileSegmentDataBlocks;
	public final int splitfileSegmentCheckBlocks;
	public final ClientEventProducer eventProducer;
	/** Interesting tradeoff, see comments at top of Node.java. */
	public final boolean cacheLocalRequests;
	public final USKManager uskManager;
	
	public InserterContext(BucketFactory bf, BucketFactory persistentBF, PersistentFileTracker tracker, RandomSource random,
			int maxRetries, int rnfsToSuccess, int maxThreads, int splitfileSegmentDataBlocks, int splitfileSegmentCheckBlocks,
			ClientEventProducer eventProducer, boolean cacheLocalRequests, USKManager uskManager) {
		this.bf = bf;
		this.persistentFileTracker = tracker;
		this.persistentBucketFactory = persistentBF;
		this.uskManager = uskManager;
		this.random = random;
		dontCompress = false;
		splitfileAlgorithm = Metadata.SPLITFILE_ONION_STANDARD;
		this.consecutiveRNFsCountAsSuccess = rnfsToSuccess;
		this.maxInsertRetries = maxRetries;
		this.maxSplitInsertThreads = maxThreads;
		this.eventProducer = eventProducer;
		this.splitfileSegmentDataBlocks = splitfileSegmentDataBlocks;
		this.splitfileSegmentCheckBlocks = splitfileSegmentCheckBlocks;
		this.cacheLocalRequests = cacheLocalRequests;
	}

	public InserterContext(InserterContext ctx, SimpleEventProducer producer, boolean forceNonPersistent) {
		this.persistentFileTracker = forceNonPersistent ? new NullPersistentFileTracker() : ctx.persistentFileTracker;
		this.uskManager = ctx.uskManager;
		this.bf = ctx.bf;
		this.persistentBucketFactory = forceNonPersistent ? ctx.bf : ctx.persistentBucketFactory;
		this.random = ctx.random;
		this.dontCompress = ctx.dontCompress;
		this.splitfileAlgorithm = ctx.splitfileAlgorithm;
		this.consecutiveRNFsCountAsSuccess = ctx.consecutiveRNFsCountAsSuccess;
		this.maxInsertRetries = ctx.maxInsertRetries;
		this.maxSplitInsertThreads = ctx.maxSplitInsertThreads;
		this.eventProducer = producer;
		this.splitfileSegmentDataBlocks = ctx.splitfileSegmentDataBlocks;
		this.splitfileSegmentCheckBlocks = ctx.splitfileSegmentCheckBlocks;
		this.cacheLocalRequests = ctx.cacheLocalRequests;
	}

	public InserterContext(InserterContext ctx, SimpleEventProducer producer) {
		this.persistentFileTracker = ctx.persistentFileTracker;
		this.uskManager = ctx.uskManager;
		this.bf = ctx.bf;
		this.persistentBucketFactory = ctx.persistentBucketFactory;
		this.random = ctx.random;
		this.dontCompress = ctx.dontCompress;
		this.splitfileAlgorithm = ctx.splitfileAlgorithm;
		this.consecutiveRNFsCountAsSuccess = ctx.consecutiveRNFsCountAsSuccess;
		this.maxInsertRetries = ctx.maxInsertRetries;
		this.maxSplitInsertThreads = ctx.maxSplitInsertThreads;
		this.eventProducer = producer;
		this.splitfileSegmentDataBlocks = ctx.splitfileSegmentDataBlocks;
		this.splitfileSegmentCheckBlocks = ctx.splitfileSegmentCheckBlocks;
		this.cacheLocalRequests = ctx.cacheLocalRequests;
	}

}
