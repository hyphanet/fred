package freenet.client;

import freenet.node.SimpleLowLevelClient;
import freenet.support.BucketFactory;

/** Context for a Fetcher */
public class FetcherContext {
	
	final SimpleLowLevelClient client;
	final long maxOutputLength;
	final long maxTempLength;
	final ArchiveManager archiveManager;
	final BucketFactory bucketFactory;
	final int maxRecursionLevel;
	final int maxArchiveRestarts;
	final boolean dontEnterImplicitArchives;
	
	public FetcherContext(SimpleLowLevelClient client, long curMaxLength, 
			long curMaxTempLength, int maxRecursionLevel, int maxArchiveRestarts,
			boolean dontEnterImplicitArchives,
			ArchiveManager archiveManager, BucketFactory bucketFactory) {
		this.client = client;
		this.maxOutputLength = curMaxLength;
		this.maxTempLength = curMaxTempLength;
		this.archiveManager = archiveManager;
		this.bucketFactory = bucketFactory;
		this.maxRecursionLevel = maxRecursionLevel;
		this.maxArchiveRestarts = maxArchiveRestarts;
		this.dontEnterImplicitArchives = dontEnterImplicitArchives;
	}

}
