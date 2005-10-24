package freenet.client;

import freenet.node.SimpleLowLevelClient;
import freenet.support.BucketFactory;

/** Context for a Fetcher */
public class FetcherContext {
	
	final SimpleLowLevelClient client;
	final long maxOutputLength;
	final long maxTempLength;
	final int maxRedirects;
	final int maxLevels;
	final int maxArchiveRecursionLevel;
	final ArchiveManager archiveManager;
	final BucketFactory bucketFactory;
	final int maxArchiveRestarts;
	
	public FetcherContext(SimpleLowLevelClient client, long curMaxLength, 
			long curMaxTempLength, int maxRedirects, int maxLevels, int maxArchives,
			int maxArchiveRestarts, ArchiveManager archiveManager, 
			BucketFactory bucketFactory) {
		this.client = client;
		this.maxOutputLength = curMaxLength;
		this.maxTempLength = curMaxTempLength;
		this.maxRedirects = maxRedirects;
		this.maxLevels = maxLevels;
		this.maxArchiveRecursionLevel = maxArchives;
		this.maxArchiveRestarts = maxArchiveRestarts;
		this.archiveManager = archiveManager;
		this.bucketFactory = bucketFactory;
	}

}
