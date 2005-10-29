package freenet.client;

import freenet.crypt.RandomSource;
import freenet.node.SimpleLowLevelClient;
import freenet.support.BucketFactory;

/** Context for a Fetcher. Contains all the settings a Fetcher needs to know about. */
public class FetcherContext {

	/** Low-level client to send low-level requests to. */
	final SimpleLowLevelClient client;
	final long maxOutputLength;
	final long maxTempLength;
	final ArchiveManager archiveManager;
	final BucketFactory bucketFactory;
	final int maxRecursionLevel;
	final int maxArchiveRestarts;
	final boolean dontEnterImplicitArchives;
	final RandomSource random;
	
	public FetcherContext(SimpleLowLevelClient client, long curMaxLength, 
			long curMaxTempLength, int maxRecursionLevel, int maxArchiveRestarts,
			boolean dontEnterImplicitArchives, RandomSource random,
			ArchiveManager archiveManager, BucketFactory bucketFactory) {
		this.client = client;
		this.maxOutputLength = curMaxLength;
		this.maxTempLength = curMaxTempLength;
		this.archiveManager = archiveManager;
		this.bucketFactory = bucketFactory;
		this.maxRecursionLevel = maxRecursionLevel;
		this.maxArchiveRestarts = maxArchiveRestarts;
		this.dontEnterImplicitArchives = dontEnterImplicitArchives;
		this.random = random;
	}

}
