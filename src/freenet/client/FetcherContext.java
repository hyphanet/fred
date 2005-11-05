package freenet.client;

import freenet.crypt.RandomSource;
import freenet.node.SimpleLowLevelClient;
import freenet.support.BucketFactory;

/** Context for a Fetcher. Contains all the settings a Fetcher needs to know about. */
public class FetcherContext implements Cloneable {

	static final int SPLITFILE_DEFAULT_BLOCK_MASK = 1;
	static final int SPLITFILE_DEFAULT_MASK = 2;
	static final int SPLITFILE_USE_LENGTHS_MASK = 3;
	/** Low-level client to send low-level requests to. */
	final SimpleLowLevelClient client;
	final long maxOutputLength;
	final long maxTempLength;
	final ArchiveManager archiveManager;
	final BucketFactory bucketFactory;
	final int maxRecursionLevel;
	final int maxArchiveRestarts;
	final boolean dontEnterImplicitArchives;
	final int maxSplitfileThreads;
	final int maxSplitfileBlockRetries;
	final int maxNonSplitfileRetries;
	final RandomSource random;
	final boolean allowSplitfiles;
	final boolean followRedirects;
	final boolean localRequestOnly;
	/** Whether to allow non-full blocks, or blocks which are not direct CHKs, in splitfiles.
	 * Set by the splitfile metadata and the mask constructor, so we don't need to pass it in. */
	final boolean splitfileUseLengths;
	
	public FetcherContext(SimpleLowLevelClient client, long curMaxLength, 
			long curMaxTempLength, int maxRecursionLevel, int maxArchiveRestarts,
			boolean dontEnterImplicitArchives, int maxSplitfileThreads,
			int maxSplitfileBlockRetries, int maxNonSplitfileRetries,
			boolean allowSplitfiles, boolean followRedirects, boolean localRequestOnly,
			RandomSource random, ArchiveManager archiveManager, BucketFactory bucketFactory) {
		this.client = client;
		this.maxOutputLength = curMaxLength;
		this.maxTempLength = curMaxTempLength;
		this.archiveManager = archiveManager;
		this.bucketFactory = bucketFactory;
		this.maxRecursionLevel = maxRecursionLevel;
		this.maxArchiveRestarts = maxArchiveRestarts;
		this.dontEnterImplicitArchives = dontEnterImplicitArchives;
		this.random = random;
		this.maxSplitfileThreads = maxSplitfileThreads;
		this.maxSplitfileBlockRetries = maxSplitfileBlockRetries;
		this.maxNonSplitfileRetries = maxNonSplitfileRetries;
		this.allowSplitfiles = allowSplitfiles;
		this.followRedirects = followRedirects;
		this.localRequestOnly = localRequestOnly;
		this.splitfileUseLengths = false;
	}

	public FetcherContext(FetcherContext ctx, int maskID) {
		if(maskID == SPLITFILE_DEFAULT_BLOCK_MASK) {
			this.client = ctx.client;
			this.maxOutputLength = ctx.maxOutputLength;
			this.maxTempLength = ctx.maxTempLength;
			this.archiveManager = ctx.archiveManager;
			this.bucketFactory = ctx.bucketFactory;
			this.maxRecursionLevel = 0;
			this.maxArchiveRestarts = 0;
			this.dontEnterImplicitArchives = true;
			this.random = ctx.random;
			this.maxSplitfileThreads = 0;
			this.maxSplitfileBlockRetries = 0;
			this.maxNonSplitfileRetries = ctx.maxNonSplitfileRetries;
			this.allowSplitfiles = false;
			this.followRedirects = false;
			this.localRequestOnly = ctx.localRequestOnly;
			this.splitfileUseLengths = false;
		} else if(maskID == SPLITFILE_DEFAULT_MASK) {
			this.client = ctx.client;
			this.maxOutputLength = ctx.maxOutputLength;
			this.maxTempLength = ctx.maxTempLength;
			this.archiveManager = ctx.archiveManager;
			this.bucketFactory = ctx.bucketFactory;
			this.maxRecursionLevel = ctx.maxRecursionLevel;
			this.maxArchiveRestarts = ctx.maxArchiveRestarts;
			this.dontEnterImplicitArchives = ctx.dontEnterImplicitArchives;
			this.random = ctx.random;
			this.maxSplitfileThreads = ctx.maxSplitfileThreads;
			this.maxSplitfileBlockRetries = ctx.maxSplitfileBlockRetries;
			this.maxNonSplitfileRetries = ctx.maxNonSplitfileRetries;
			this.allowSplitfiles = ctx.allowSplitfiles;
			this.followRedirects = ctx.followRedirects;
			this.localRequestOnly = ctx.localRequestOnly;
			this.splitfileUseLengths = false;
		} else if(maskID == SPLITFILE_USE_LENGTHS_MASK) {
			this.client = ctx.client;
			this.maxOutputLength = ctx.maxOutputLength;
			this.maxTempLength = ctx.maxTempLength;
			this.archiveManager = ctx.archiveManager;
			this.bucketFactory = ctx.bucketFactory;
			this.maxRecursionLevel = ctx.maxRecursionLevel;
			this.maxArchiveRestarts = ctx.maxArchiveRestarts;
			this.dontEnterImplicitArchives = ctx.dontEnterImplicitArchives;
			this.random = ctx.random;
			this.maxSplitfileThreads = ctx.maxSplitfileThreads;
			this.maxSplitfileBlockRetries = ctx.maxSplitfileBlockRetries;
			this.maxNonSplitfileRetries = ctx.maxNonSplitfileRetries;
			this.allowSplitfiles = ctx.allowSplitfiles;
			this.followRedirects = ctx.followRedirects;
			this.localRequestOnly = ctx.localRequestOnly;
			this.splitfileUseLengths = true;
		} else throw new IllegalArgumentException();
	}

	/** Make public, but just call parent for a field for field copy */
	public Object clone() {
		try {
			return super.clone();
		} catch (CloneNotSupportedException e) {
			// Impossible
			throw new Error(e);
		}
	}
	
}
