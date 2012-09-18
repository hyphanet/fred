package freenet.clients.http;

import freenet.client.FetchException;
import freenet.support.api.Bucket;

/** The result of fproxy waiting for a fetch: It can either be the final data, or it
 * can be the progress of the fetch so far. This is a snapshot, so should be
 * consistent but could be out of date: if the snapshot says it is in progress,
 * it might actually be finished. Note that close() must be called when fproxy
 * has finished with the data (and is the only method that actually calls back 
 * to the freenet.clients.http.FProxyFetchInProgress ).
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 */
public class FProxyFetchResult {

	/** If we have fetched the data, we know this. If we haven't, we might know it. */
	public final String mimeType;
	
	/** If we have fetched the data, we know this. If we haven't, we might know it. */
	public final long size;
	
	/** If we have fetched the data */
	final Bucket data;
	
	/** If we have not fetched the data */
	/** Creation time */
	public final long timeStarted;
	/** Gone to network? */
	public final boolean goneToNetwork;
	/** Total blocks */
	public final int totalBlocks;
	/** Required blocks */
	public final int requiredBlocks;
	/** Fetched blocks */
	public final int fetchedBlocks;
	/** Failed blocks */
	public final int failedBlocks;
	/** Fatally failed blocks */
	public final int fatallyFailedBlocks;
	/** Finalized blocks? */
	public final boolean finalizedBlocks;
	
	/** Number of times this has been used */
	private int fetchedCount;
	
	/** Failed */
	public final FetchException failed;
	
	final FProxyFetchInProgress progress;
	final boolean hasWaited;

	public final long eta;
	
	/** At the time of creating the snapshot, has it finished? */
	private final boolean finished;

	/** Constructor when we are returning the data */
	FProxyFetchResult(FProxyFetchInProgress parent, Bucket data, String mimeType, long timeStarted, boolean goneToNetwork, long eta, boolean hasWaited) {
		assert(data != null);
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
		finished = true;
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
		finished = (failed != null);
	}
	
	/** Must be called when fproxy has finished with the data */
	public void close() {
		progress.close(this);
	}

	public boolean hasData() {
		return data != null;
	}
	
	public Bucket getData() {
		return data;
	}

	public boolean hasWaited() {
		return hasWaited;
	}
	
	public boolean isFinished(){
		return finished;
	}

	public void setFetchCount(int fetched) {
		this.fetchedCount = fetched;
	}
	
	public int getFetchCount() {
		return fetchedCount;
	}
	
}
