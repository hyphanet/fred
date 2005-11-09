package freenet.client;

import freenet.crypt.RandomSource;
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
	final int maxInsertBlockRetries;
	final int maxSplitInsertThreads;
	
	public InserterContext(SimpleLowLevelClient client, BucketFactory bf, RandomSource random,
			int maxRetries, int maxThreads) {
		this.client = client;
		this.bf = bf;
		this.random = random;
		dontCompress = false;
		splitfileAlgorithm = Metadata.SPLITFILE_ONION_STANDARD;
		this.maxInsertBlockRetries = maxRetries;
		this.maxSplitInsertThreads = maxThreads;
	}

}
