/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import java.util.Set;

import com.db4o.ObjectContainer;

import freenet.client.async.BlockSet;
import freenet.client.events.ClientEventProducer;
import freenet.client.events.SimpleEventProducer;
import freenet.client.filter.FoundURICallback;
import freenet.client.filter.TagReplacerCallback;
import freenet.support.api.BucketFactory;

/** Context for a Fetcher. Contains all the settings a Fetcher needs to know about. */
// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
public class FetchContext implements Cloneable {

	public static final int IDENTICAL_MASK = 0;
	public static final int SPLITFILE_DEFAULT_BLOCK_MASK = 1;
	public static final int SPLITFILE_DEFAULT_MASK = 2;
	public static final int SET_RETURN_ARCHIVES = 4;
	/** Maximum length of the final returned data */
	public long maxOutputLength;
	/** Maximum length of data fetched in order to obtain the final data - metadata, containers, etc. */
	public long maxTempLength;
	public int maxRecursionLevel;
	public int maxArchiveRestarts;
	/** Maximum number of containers to fetch during a request */
	public int maxArchiveLevels;
	public boolean dontEnterImplicitArchives;
	/** Maximum number of retries (after the original attempt) for a
	 * splitfile block. -1 = try forever or until success or a fatal error.
	 * A fatal error is either an internal error (problem with the node) or
	 * something resulting from the original data being corrupt as inserted.
	 * So with retries = -1 we will not report Data not found, Route not
	 * found, All data not found, etc, because these are nonfatal errors and
	 * we will retry. Note that after every 3 attempts the request is put
	 * on the cooldown queue for 30 minutes, so the cost of retries = -1 is
	 * really not that high. */
	public int maxSplitfileBlockRetries;
	/** Maximum number of retries (after the original attempt) for a
	 * non-splitfile block. -1 = try forever or until success or a fatal
	 * error.. -1 = try forever or until success or a fatal error.
	 * A fatal error is either an internal error (problem with the node) or
	 * something resulting from the original data being corrupt as inserted.
	 * So with retries = -1 we will not report Data not found, Route not
	 * found, All data not found, etc, because these are nonfatal errors and
	 * we will retry. Note that after every 3 attempts the request is put
	 * on the cooldown queue for 30 minutes, so the cost of retries = -1 is
	 * really not that high. */
	public int maxNonSplitfileRetries;
	public final int maxUSKRetries;
	/** Whether to download splitfiles */
	public boolean allowSplitfiles;
	/** Whether to follow simple redirects */
	public boolean followRedirects;
	/** If true, only read from the datastore and caches, do not send the request to the network */
	public boolean localRequestOnly;
	/** If true, send the request to the network without checking whether the data is in the local store */
	public boolean ignoreStore;
	/** Client events will be published to this, you can subscribe to them */
	public final ClientEventProducer eventProducer;
	public int maxMetadataSize;
	/** Maximum number of data blocks per segment for splitfiles */
	public int maxDataBlocksPerSegment;
	/** Maximum number of check blocks per segment for splitfiles. */
	public int maxCheckBlocksPerSegment;
	/** If true, and we get a ZIP manifest, and we have no meta-strings left, then
	 * return the manifest contents as data. */
	public boolean returnZIPManifests;
	/*If true, filter the fetched data*/
	public boolean filterData;
	public final boolean ignoreTooManyPathComponents;
	/** If set, contains a set of blocks to be consulted before checking the datastore. */
	public final BlockSet blocks;
	/** If non-null, the request will be stopped if it has a MIME type that is not one of these,
	 * or has no MIME type. */
	public Set<String> allowedMIMETypes;
	/** If not-null, the request, if it requires a charset for filtration, will be assumed
	 * to use this charset */
	public String charset;
	/** Do we have responsibility for removing the ClientEventProducer from the database? */
	private final boolean hasOwnEventProducer;
	/** Can this request write to the client-cache? We don't store all requests in the client cache,
	 * in particular big stuff usually isn't written to it, to maximise its effectiveness. */
	public boolean canWriteClientCache;
	/**Prefetch hook for HTML documents. Only really necessary for FProxy's web-pushing*/
	public FoundURICallback prefetchHook;
	/**Callback needed for web-pushing*/
	public TagReplacerCallback tagReplacer;
	/**Force the content fiter to use this MIME type*/
	public String overrideMIME;

	public FetchContext(long curMaxLength,
			long curMaxTempLength, int maxMetadataSize, int maxRecursionLevel, int maxArchiveRestarts, int maxArchiveLevels,
			boolean dontEnterImplicitArchives,
			int maxSplitfileBlockRetries, int maxNonSplitfileRetries, int maxUSKRetries,
			boolean allowSplitfiles, boolean followRedirects, boolean localRequestOnly,
			boolean filterData, int maxDataBlocksPerSegment, int maxCheckBlocksPerSegment,
			BucketFactory bucketFactory,
			ClientEventProducer producer,
			boolean ignoreTooManyPathComponents, boolean canWriteClientCache, String charset, String overrideMIME) {
		this.blocks = null;
		this.maxOutputLength = curMaxLength;
		this.maxTempLength = curMaxTempLength;
		this.maxMetadataSize = maxMetadataSize;
		this.maxRecursionLevel = maxRecursionLevel;
		this.maxArchiveRestarts = maxArchiveRestarts;
		this.maxArchiveLevels = maxArchiveLevels;
		this.dontEnterImplicitArchives = dontEnterImplicitArchives;
		this.maxSplitfileBlockRetries = maxSplitfileBlockRetries;
		this.maxNonSplitfileRetries = maxNonSplitfileRetries;
		this.maxUSKRetries = maxUSKRetries;
		this.allowSplitfiles = allowSplitfiles;
		this.followRedirects = followRedirects;
		this.localRequestOnly = localRequestOnly;
		this.eventProducer = producer;
		this.maxDataBlocksPerSegment = maxDataBlocksPerSegment;
		this.maxCheckBlocksPerSegment = maxCheckBlocksPerSegment;
		this.filterData = filterData;
		this.ignoreTooManyPathComponents = ignoreTooManyPathComponents;
		this.canWriteClientCache = canWriteClientCache;
		this.charset = charset;
		this.overrideMIME = overrideMIME;
		hasOwnEventProducer = true;
	}

	/** Copy a FetchContext.
	 * @param ctx
	 * @param maskID
	 * @param keepProducer
	 * @param blocks Storing a BlockSet to the database is not supported, see comments on SimpleBlockSet.objectCanNew().
	 */
	public FetchContext(FetchContext ctx, int maskID, boolean keepProducer, BlockSet blocks) {
		if(keepProducer)
			this.eventProducer = ctx.eventProducer;
		else
			this.eventProducer = new SimpleEventProducer();
		hasOwnEventProducer = !keepProducer;
		this.ignoreTooManyPathComponents = ctx.ignoreTooManyPathComponents;
		if(blocks != null)
			this.blocks = blocks;
		else
			this.blocks = ctx.blocks;

		this.allowedMIMETypes = ctx.allowedMIMETypes;
		this.maxUSKRetries = ctx.maxUSKRetries;
		this.localRequestOnly = ctx.localRequestOnly;
		this.maxArchiveLevels = ctx.maxArchiveLevels;
		this.maxMetadataSize = ctx.maxMetadataSize;
		this.maxNonSplitfileRetries = ctx.maxNonSplitfileRetries;
		this.maxOutputLength = ctx.maxOutputLength;
		this.maxSplitfileBlockRetries = ctx.maxSplitfileBlockRetries;
		this.maxTempLength = ctx.maxTempLength;
		this.allowSplitfiles = ctx.allowSplitfiles;
		this.dontEnterImplicitArchives = ctx.dontEnterImplicitArchives;
		this.followRedirects = ctx.followRedirects;
		this.maxArchiveRestarts = ctx.maxArchiveRestarts;
		this.maxCheckBlocksPerSegment = ctx.maxCheckBlocksPerSegment;
		this.maxDataBlocksPerSegment = ctx.maxDataBlocksPerSegment;
		this.filterData = ctx.filterData;
		this.maxRecursionLevel = ctx.maxRecursionLevel;
		this.returnZIPManifests = ctx.returnZIPManifests;
		this.canWriteClientCache = ctx.canWriteClientCache;
		this.prefetchHook = ctx.prefetchHook;
		this.tagReplacer = ctx.tagReplacer;
		this.overrideMIME = ctx.overrideMIME;

		if(maskID == IDENTICAL_MASK || maskID == SPLITFILE_DEFAULT_MASK) {
			// DEFAULT
		} else if(maskID == SPLITFILE_DEFAULT_BLOCK_MASK) {
			this.maxRecursionLevel = 1;
			this.maxArchiveRestarts = 0;
			this.dontEnterImplicitArchives = true;
			this.allowSplitfiles = false;
			this.followRedirects = false;
			this.maxDataBlocksPerSegment = 0;
			this.maxCheckBlocksPerSegment = 0;
			this.returnZIPManifests = false;
		} else if (maskID == SET_RETURN_ARCHIVES) {
			this.returnZIPManifests = true;
		}
		else throw new IllegalArgumentException();
	}

	/** Make public, but just call parent for a field for field copy */
	@Override
	public FetchContext clone() {
		try {
			return (FetchContext) super.clone();
		} catch (CloneNotSupportedException e) {
			// Impossible
			throw new Error(e);
		}
	}

	public void removeFrom(ObjectContainer container) {
		if(hasOwnEventProducer) {
			container.activate(eventProducer, 1);
			eventProducer.removeFrom(container);
		}
		// Storing a BlockSet to the database is not supported, see comments on SimpleBlockSet.objectCanNew().
		// allowedMIMETypes is passed in, whoever passes it in is responsible for deleting it.
		container.delete(this);
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + (allowSplitfiles ? 1231 : 1237);
		result = prime
				* result
				+ ((allowedMIMETypes == null) ? 0 : allowedMIMETypes.hashCode());
		result = prime * result + ((blocks == null) ? 0 : blocks.hashCode());
		result = prime * result + (canWriteClientCache ? 1231 : 1237);
		result = prime * result + ((charset == null) ? 0 : charset.hashCode());
		result = prime * result + (dontEnterImplicitArchives ? 1231 : 1237);
		result = prime * result
				+ ((eventProducer == null) ? 0 : eventProducer.hashCode());
		result = prime * result + (filterData ? 1231 : 1237);
		result = prime * result + (followRedirects ? 1231 : 1237);
		result = prime * result + (hasOwnEventProducer ? 1231 : 1237);
		result = prime * result + (ignoreStore ? 1231 : 1237);
		result = prime * result + (ignoreTooManyPathComponents ? 1231 : 1237);
		result = prime * result + (localRequestOnly ? 1231 : 1237);
		result = prime * result + maxArchiveLevels;
		result = prime * result + maxArchiveRestarts;
		result = prime * result + maxCheckBlocksPerSegment;
		result = prime * result + maxDataBlocksPerSegment;
		result = prime * result + maxMetadataSize;
		result = prime * result + maxNonSplitfileRetries;
		result = prime * result
				+ (int) (maxOutputLength ^ (maxOutputLength >>> 32));
		result = prime * result + maxRecursionLevel;
		result = prime * result + maxSplitfileBlockRetries;
		result = prime * result
				+ (int) (maxTempLength ^ (maxTempLength >>> 32));
		result = prime * result + maxUSKRetries;
		result = prime * result
				+ ((overrideMIME == null) ? 0 : overrideMIME.hashCode());
		result = prime * result
				+ ((prefetchHook == null) ? 0 : prefetchHook.hashCode());
		result = prime * result + (returnZIPManifests ? 1231 : 1237);
		result = prime * result
				+ ((tagReplacer == null) ? 0 : tagReplacer.hashCode());
		return result;
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (!(obj instanceof FetchContext)) {
			return false;
		}
		FetchContext other = (FetchContext) obj;
		if (allowSplitfiles != other.allowSplitfiles) {
			return false;
		}
		if (allowedMIMETypes == null) {
			if (other.allowedMIMETypes != null) {
				return false;
			}
		} else if (!allowedMIMETypes.equals(other.allowedMIMETypes)) {
			return false;
		}
		if (blocks == null) {
			if (other.blocks != null) {
				return false;
			}
		} else if (!blocks.equals(other.blocks)) {
			return false;
		}
		if (canWriteClientCache != other.canWriteClientCache) {
			return false;
		}
		if (charset == null) {
			if (other.charset != null) {
				return false;
			}
		} else if (!charset.equals(other.charset)) {
			return false;
		}
		if (dontEnterImplicitArchives != other.dontEnterImplicitArchives) {
			return false;
		}
		if (eventProducer == null) {
			if (other.eventProducer != null) {
				return false;
			}
		} else if (!eventProducer.equals(other.eventProducer)) {
			return false;
		}
		if (filterData != other.filterData) {
			return false;
		}
		if (followRedirects != other.followRedirects) {
			return false;
		}
		if (hasOwnEventProducer != other.hasOwnEventProducer) {
			return false;
		}
		if (ignoreStore != other.ignoreStore) {
			return false;
		}
		if (ignoreTooManyPathComponents != other.ignoreTooManyPathComponents) {
			return false;
		}
		if (localRequestOnly != other.localRequestOnly) {
			return false;
		}
		if (maxArchiveLevels != other.maxArchiveLevels) {
			return false;
		}
		if (maxArchiveRestarts != other.maxArchiveRestarts) {
			return false;
		}
		if (maxCheckBlocksPerSegment != other.maxCheckBlocksPerSegment) {
			return false;
		}
		if (maxDataBlocksPerSegment != other.maxDataBlocksPerSegment) {
			return false;
		}
		if (maxMetadataSize != other.maxMetadataSize) {
			return false;
		}
		if (maxNonSplitfileRetries != other.maxNonSplitfileRetries) {
			return false;
		}
		if (maxOutputLength != other.maxOutputLength) {
			return false;
		}
		if (maxRecursionLevel != other.maxRecursionLevel) {
			return false;
		}
		if (maxSplitfileBlockRetries != other.maxSplitfileBlockRetries) {
			return false;
		}
		if (maxTempLength != other.maxTempLength) {
			return false;
		}
		if (maxUSKRetries != other.maxUSKRetries) {
			return false;
		}
		if (overrideMIME == null) {
			if (other.overrideMIME != null) {
				return false;
			}
		} else if (!overrideMIME.equals(other.overrideMIME)) {
			return false;
		}
		if (prefetchHook == null) {
			if (other.prefetchHook != null) {
				return false;
			}
		} else if (!prefetchHook.equals(other.prefetchHook)) {
			return false;
		}
		if (returnZIPManifests != other.returnZIPManifests) {
			return false;
		}
		if (tagReplacer == null) {
			if (other.tagReplacer != null) {
				return false;
			}
		} else if (!tagReplacer.equals(other.tagReplacer)) {
			return false;
		}
		return true;
	}

}
