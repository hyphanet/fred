package freenet.clients.http;

import freenet.client.FetchException;
import freenet.support.api.Bucket;

/** The result of fproxy waiting for a fetch: It can either be the final data, or it
 * can be the progress of the fetch so far.
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 */
public class FProxyFetchResult {

	/** If we have fetched the data, we know this. If we haven't, we might know it. */
	final String mimeType;
	
	/** If we have fetched the data, we know this. If we haven't, we might know it. */
	final long size;
	
	/** If we have fetched the data */
	final Bucket data;
	
	/** If we have not fetched the data */
	/** Creation time */
	final long timeStarted;
	/** Gone to network? */
	final boolean goneToNetwork;
	/** Total blocks */
	final int totalBlocks;
	/** Required blocks */
	final int requiredBlocks;
	/** Fetched blocks */
	final int fetchedBlocks;
	/** Failed blocks */
	final int failedBlocks;
	/** Fatally failed blocks */
	final int fatallyFailedBlocks;
	/** Finalized blocks? */
	final boolean finalizedBlocks;
	
	
	/** Failed */
	final FetchException failed;
	
	final FProxyFetchInProgress progress;
	final boolean hasWaited;

	final long eta;

	/** Constructor when we are returning the data */
	FProxyFetchResult(FProxyFetchInProgress parent, Bucket data, String mimeType, long timeStarted, boolean goneToNetwork, long eta, boolean hasWaited) {
		this.data = data;
		this.mimeType = mimeType;
		this.size = data.size();
		this.timeStarted = timeStarted;
		this.goneToNetwork = goneToNetwork;
		totalBlocks = requiredBlocks = fetchedBlocks = failedBlocks = fatallyFailedBlocks = 0;
		finalizedBlocks = true;
		failed = null;
		this.progress = parent;
		this.eta = eta;
		this.hasWaited = hasWaited;
	}

	/** Constructor when we are not returning the data, because it is still running or it failed */
	FProxyFetchResult(FProxyFetchInProgress parent, String mimeType, long size, long timeStarted, boolean goneToNetwork, int totalBlocks, int requiredBlocks, int fetchedBlocks, int failedBlocks, int fatallyFailedBlocks, boolean finalizedBlocks, FetchException failed, long eta, boolean hasWaited) {
		this.data = null;
		this.mimeType = mimeType;
		this.size = size;
		this.timeStarted = timeStarted;
		this.goneToNetwork = goneToNetwork;
		this.totalBlocks = totalBlocks;
		this.requiredBlocks = requiredBlocks;
		this.fetchedBlocks = fetchedBlocks;
		this.failedBlocks = failedBlocks;
		this.fatallyFailedBlocks = fatallyFailedBlocks;
		this.finalizedBlocks = finalizedBlocks;
		this.failed = failed;
		this.progress = parent;
		this.eta = eta;
		this.hasWaited = hasWaited;
	}
	
	/** Must be called when fproxy has finished with the data */
	public void close() {
		progress.close(this);
	}

	public boolean hasData() {
		return data != null;
	}

	public boolean hasWaited() {
		return hasWaited;
	}
	
}
