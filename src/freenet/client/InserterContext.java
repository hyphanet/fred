package freenet.client;

import freenet.client.events.ClientEventProducer;
import freenet.crypt.RandomSource;
import freenet.node.RequestStarterClient;
import freenet.node.SimpleLowLevelClient;
import freenet.support.BucketFactory;

/** Context object for an insert operation, including both simple and multi-file inserts */
public class InserterContext {

	final SimpleLowLevelClient client;
	final BucketFactory bf;
	/** If true, don't try to compress the data */
	final boolean dontCompress;
	final RandomSource random;
	final short splitfileAlgorithm;
	public int maxInsertRetries;
	final int maxSplitInsertThreads;
	final int consecutiveRNFsCountAsSuccess;
	final int splitfileSegmentDataBlocks;
	final int splitfileSegmentCheckBlocks;
	final ClientEventProducer eventProducer;
	final RequestStarterClient starterClient;
	/** Interesting tradeoff, see comments at top of Node.java. */
	final boolean cacheLocalRequests;
	private boolean cancelled;
	
	public InserterContext(SimpleLowLevelClient client, BucketFactory bf, RandomSource random,
			int maxRetries, int rnfsToSuccess, int maxThreads, int splitfileSegmentDataBlocks, int splitfileSegmentCheckBlocks,
			ClientEventProducer eventProducer, RequestStarterClient sctx, boolean cacheLocalRequests) {
		this.client = client;
		this.bf = bf;
		this.random = random;
		dontCompress = false;
		splitfileAlgorithm = Metadata.SPLITFILE_ONION_STANDARD;
		this.consecutiveRNFsCountAsSuccess = rnfsToSuccess;
		this.maxInsertRetries = maxRetries;
		this.maxSplitInsertThreads = maxThreads;
		this.eventProducer = eventProducer;
		this.splitfileSegmentDataBlocks = splitfileSegmentDataBlocks;
		this.splitfileSegmentCheckBlocks = splitfileSegmentCheckBlocks;
		this.starterClient = sctx;
		this.cacheLocalRequests = cacheLocalRequests;
	}

	public InserterContext(InserterContext ctx) {
		this.client = ctx.client;
		this.bf = ctx.bf;
		this.random = ctx.random;
		this.dontCompress = ctx.dontCompress;
		this.splitfileAlgorithm = ctx.splitfileAlgorithm;
		this.consecutiveRNFsCountAsSuccess = ctx.consecutiveRNFsCountAsSuccess;
		this.maxInsertRetries = ctx.maxInsertRetries;
		this.maxSplitInsertThreads = ctx.maxSplitInsertThreads;
		this.eventProducer = ctx.eventProducer;
		this.splitfileSegmentDataBlocks = ctx.splitfileSegmentDataBlocks;
		this.splitfileSegmentCheckBlocks = ctx.splitfileSegmentCheckBlocks;
		this.starterClient = ctx.starterClient;
		this.cacheLocalRequests = ctx.cacheLocalRequests;
	}

	public void cancel() {
		cancelled = true;
	}

	public boolean isCancelled() {
		return cancelled;
	}
	
}
