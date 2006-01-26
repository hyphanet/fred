package freenet.client;

import freenet.client.events.ClientEventProducer;
import freenet.crypt.RandomSource;
import freenet.support.BucketFactory;

/** Context object for an insert operation, including both simple and multi-file inserts */
public class InserterContext {

	public final BucketFactory bf;
	/** If true, don't try to compress the data */
	public final boolean dontCompress;
	public final RandomSource random;
	public final short splitfileAlgorithm;
	public int maxInsertRetries;
	final int maxSplitInsertThreads;
	public final int consecutiveRNFsCountAsSuccess;
	public final int splitfileSegmentDataBlocks;
	public final int splitfileSegmentCheckBlocks;
	final ClientEventProducer eventProducer;
	/** Interesting tradeoff, see comments at top of Node.java. */
	public final boolean cacheLocalRequests;
	private boolean cancelled;
	
	public InserterContext(BucketFactory bf, RandomSource random,
			int maxRetries, int rnfsToSuccess, int maxThreads, int splitfileSegmentDataBlocks, int splitfileSegmentCheckBlocks,
			ClientEventProducer eventProducer, boolean cacheLocalRequests) {
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
		this.cacheLocalRequests = cacheLocalRequests;
	}

	public InserterContext(InserterContext ctx) {
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
		this.cacheLocalRequests = ctx.cacheLocalRequests;
	}

	public void cancel() {
		cancelled = true;
	}

	public boolean isCancelled() {
		return cancelled;
	}
	
}
