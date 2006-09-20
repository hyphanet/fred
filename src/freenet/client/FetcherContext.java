package freenet.client;

import freenet.client.async.HealingQueue;
import freenet.client.async.USKManager;
import freenet.client.events.ClientEventProducer;
import freenet.client.events.SimpleEventProducer;
import freenet.crypt.RandomSource;
import freenet.support.io.BucketFactory;

/** Context for a Fetcher. Contains all the settings a Fetcher needs to know about. */
public class FetcherContext implements Cloneable {

	public static final int IDENTICAL_MASK = 0;
	public static final int SPLITFILE_DEFAULT_BLOCK_MASK = 1;
	public static final int SPLITFILE_DEFAULT_MASK = 2;
	public static final int SPLITFILE_USE_LENGTHS_MASK = 3;
	public static final int SET_RETURN_ARCHIVES = 4;
	/** Low-level client to send low-level requests to. */
	public long maxOutputLength;
	public long maxTempLength;
	public final ArchiveManager archiveManager;
	public final BucketFactory bucketFactory;
	public USKManager uskManager;
	public int maxRecursionLevel;
	public int maxArchiveRestarts;
	public int maxArchiveLevels;
	public boolean dontEnterImplicitArchives;
	public int maxSplitfileThreads;
	public int maxSplitfileBlockRetries;
	public int maxNonSplitfileRetries;
	public final RandomSource random;
	public boolean allowSplitfiles;
	public boolean followRedirects;
	public boolean localRequestOnly;
	public boolean ignoreStore;
	public final ClientEventProducer eventProducer;
	/** Whether to allow non-full blocks, or blocks which are not direct CHKs, in splitfiles.
	 * Set by the splitfile metadata and the mask constructor, so we don't need to pass it in. */
	public boolean splitfileUseLengths;
	public int maxMetadataSize;
	public int maxDataBlocksPerSegment;
	public int maxCheckBlocksPerSegment;
	public boolean cacheLocalRequests;
	/** If true, and we get a ZIP manifest, and we have no meta-strings left, then
	 * return the manifest contents as data. */
	public boolean returnZIPManifests;
	public final HealingQueue healingQueue;
	
	public FetcherContext(long curMaxLength, 
			long curMaxTempLength, int maxMetadataSize, int maxRecursionLevel, int maxArchiveRestarts, int maxArchiveLevels,
			boolean dontEnterImplicitArchives, int maxSplitfileThreads,
			int maxSplitfileBlockRetries, int maxNonSplitfileRetries,
			boolean allowSplitfiles, boolean followRedirects, boolean localRequestOnly,
			int maxDataBlocksPerSegment, int maxCheckBlocksPerSegment,
			RandomSource random, ArchiveManager archiveManager, BucketFactory bucketFactory,
			ClientEventProducer producer, boolean cacheLocalRequests, USKManager uskManager, HealingQueue hq) {
		this.maxOutputLength = curMaxLength;
		this.uskManager = uskManager;
		this.maxTempLength = curMaxTempLength;
		this.maxMetadataSize = maxMetadataSize;
		this.archiveManager = archiveManager;
		this.bucketFactory = bucketFactory;
		this.maxRecursionLevel = maxRecursionLevel;
		this.maxArchiveRestarts = maxArchiveRestarts;
		this.maxArchiveLevels = maxArchiveLevels;
		this.dontEnterImplicitArchives = dontEnterImplicitArchives;
		this.random = random;
		this.maxSplitfileThreads = maxSplitfileThreads;
		this.maxSplitfileBlockRetries = maxSplitfileBlockRetries;
		this.maxNonSplitfileRetries = maxNonSplitfileRetries;
		this.allowSplitfiles = allowSplitfiles;
		this.followRedirects = followRedirects;
		this.localRequestOnly = localRequestOnly;
		this.splitfileUseLengths = false;
		this.eventProducer = producer;
		this.maxDataBlocksPerSegment = maxDataBlocksPerSegment;
		this.maxCheckBlocksPerSegment = maxCheckBlocksPerSegment;
		this.cacheLocalRequests = cacheLocalRequests;
		this.healingQueue = hq;
	}

	public FetcherContext(FetcherContext ctx, int maskID, boolean keepProducer) {
		this.healingQueue = ctx.healingQueue;
		if(keepProducer)
			this.eventProducer = ctx.eventProducer;
		else
			this.eventProducer = new SimpleEventProducer();
		this.uskManager = ctx.uskManager;
		if(maskID == IDENTICAL_MASK) {
			this.maxOutputLength = ctx.maxOutputLength;
			this.maxMetadataSize = ctx.maxMetadataSize;
			this.maxTempLength = ctx.maxTempLength;
			this.archiveManager = ctx.archiveManager;
			this.bucketFactory = ctx.bucketFactory;
			this.maxRecursionLevel = ctx.maxRecursionLevel;
			this.maxArchiveRestarts = ctx.maxArchiveRestarts;
			this.maxArchiveLevels = ctx.maxArchiveLevels;
			this.dontEnterImplicitArchives = ctx.dontEnterImplicitArchives;
			this.random = ctx.random;
			this.maxSplitfileThreads = ctx.maxSplitfileThreads;
			this.maxSplitfileBlockRetries = ctx.maxSplitfileBlockRetries;
			this.maxNonSplitfileRetries = ctx.maxNonSplitfileRetries;
			this.allowSplitfiles = ctx.allowSplitfiles;
			this.followRedirects = ctx.followRedirects;
			this.localRequestOnly = ctx.localRequestOnly;
			this.splitfileUseLengths = ctx.splitfileUseLengths;
			this.maxDataBlocksPerSegment = ctx.maxDataBlocksPerSegment;
			this.maxCheckBlocksPerSegment = ctx.maxCheckBlocksPerSegment;
			this.cacheLocalRequests = ctx.cacheLocalRequests;
			this.returnZIPManifests = ctx.returnZIPManifests;
		} else if(maskID == SPLITFILE_DEFAULT_BLOCK_MASK) {
			this.maxOutputLength = ctx.maxOutputLength;
			this.maxMetadataSize = ctx.maxMetadataSize;
			this.maxTempLength = ctx.maxTempLength;
			this.archiveManager = ctx.archiveManager;
			this.bucketFactory = ctx.bucketFactory;
			this.maxRecursionLevel = 1;
			this.maxArchiveRestarts = 0;
			this.maxArchiveLevels = ctx.maxArchiveLevels;
			this.dontEnterImplicitArchives = true;
			this.random = ctx.random;
			this.maxSplitfileThreads = 0;
			this.maxSplitfileBlockRetries = ctx.maxSplitfileBlockRetries;
			this.maxNonSplitfileRetries = ctx.maxSplitfileBlockRetries;
			this.allowSplitfiles = false;
			this.followRedirects = false;
			this.localRequestOnly = ctx.localRequestOnly;
			this.splitfileUseLengths = false;
			this.maxDataBlocksPerSegment = 0;
			this.maxCheckBlocksPerSegment = 0;
			this.cacheLocalRequests = ctx.cacheLocalRequests;
			this.returnZIPManifests = false;
		} else if(maskID == SPLITFILE_DEFAULT_MASK) {
			this.maxOutputLength = ctx.maxOutputLength;
			this.maxTempLength = ctx.maxTempLength;
			this.maxMetadataSize = ctx.maxMetadataSize;
			this.archiveManager = ctx.archiveManager;
			this.bucketFactory = ctx.bucketFactory;
			this.maxRecursionLevel = ctx.maxRecursionLevel;
			this.maxArchiveRestarts = ctx.maxArchiveRestarts;
			this.maxArchiveLevels = ctx.maxArchiveLevels;
			this.dontEnterImplicitArchives = ctx.dontEnterImplicitArchives;
			this.random = ctx.random;
			this.maxSplitfileThreads = ctx.maxSplitfileThreads;
			this.maxSplitfileBlockRetries = ctx.maxSplitfileBlockRetries;
			this.maxNonSplitfileRetries = ctx.maxNonSplitfileRetries;
			this.allowSplitfiles = ctx.allowSplitfiles;
			this.followRedirects = ctx.followRedirects;
			this.localRequestOnly = ctx.localRequestOnly;
			this.splitfileUseLengths = false;
			this.maxDataBlocksPerSegment = ctx.maxDataBlocksPerSegment;
			this.maxCheckBlocksPerSegment = ctx.maxCheckBlocksPerSegment;
			this.cacheLocalRequests = ctx.cacheLocalRequests;
			this.returnZIPManifests = ctx.returnZIPManifests;
		} else if(maskID == SPLITFILE_USE_LENGTHS_MASK) {
			this.maxOutputLength = ctx.maxOutputLength;
			this.maxTempLength = ctx.maxTempLength;
			this.maxMetadataSize = ctx.maxMetadataSize;
			this.archiveManager = ctx.archiveManager;
			this.bucketFactory = ctx.bucketFactory;
			this.maxRecursionLevel = ctx.maxRecursionLevel;
			this.maxArchiveRestarts = ctx.maxArchiveRestarts;
			this.maxArchiveLevels = ctx.maxArchiveLevels;
			this.dontEnterImplicitArchives = ctx.dontEnterImplicitArchives;
			this.random = ctx.random;
			this.maxSplitfileThreads = ctx.maxSplitfileThreads;
			this.maxSplitfileBlockRetries = ctx.maxSplitfileBlockRetries;
			this.maxNonSplitfileRetries = ctx.maxNonSplitfileRetries;
			this.allowSplitfiles = ctx.allowSplitfiles;
			this.followRedirects = ctx.followRedirects;
			this.localRequestOnly = ctx.localRequestOnly;
			this.splitfileUseLengths = true;
			this.maxDataBlocksPerSegment = ctx.maxDataBlocksPerSegment;
			this.maxCheckBlocksPerSegment = ctx.maxCheckBlocksPerSegment;
			this.cacheLocalRequests = ctx.cacheLocalRequests;
			this.returnZIPManifests = ctx.returnZIPManifests;
		} else if (maskID == SET_RETURN_ARCHIVES) {
			this.maxOutputLength = ctx.maxOutputLength;
			this.maxMetadataSize = ctx.maxMetadataSize;
			this.maxTempLength = ctx.maxTempLength;
			this.archiveManager = ctx.archiveManager;
			this.bucketFactory = ctx.bucketFactory;
			this.maxRecursionLevel = ctx.maxRecursionLevel;
			this.maxArchiveRestarts = ctx.maxArchiveRestarts;
			this.maxArchiveLevels = ctx.maxArchiveLevels;
			this.dontEnterImplicitArchives = ctx.dontEnterImplicitArchives;
			this.random = ctx.random;
			this.maxSplitfileThreads = ctx.maxSplitfileThreads;
			this.maxSplitfileBlockRetries = ctx.maxSplitfileBlockRetries;
			this.maxNonSplitfileRetries = ctx.maxNonSplitfileRetries;
			this.allowSplitfiles = ctx.allowSplitfiles;
			this.followRedirects = ctx.followRedirects;
			this.localRequestOnly = ctx.localRequestOnly;
			this.splitfileUseLengths = ctx.splitfileUseLengths;
			this.maxDataBlocksPerSegment = ctx.maxDataBlocksPerSegment;
			this.maxCheckBlocksPerSegment = ctx.maxCheckBlocksPerSegment;
			this.cacheLocalRequests = ctx.cacheLocalRequests;
			this.returnZIPManifests = true;
		}
		else throw new IllegalArgumentException();
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
