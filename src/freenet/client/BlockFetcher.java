/**
 * 
 */
package freenet.client;

import freenet.keys.FreenetURI;
import freenet.support.Bucket;
import freenet.support.Logger;

public class BlockFetcher extends StdSplitfileBlock {

	private final Segment segment;
	final FreenetURI uri;
	final boolean dontEnterImplicitArchives;
	int completedTries;
	boolean actuallyFetched;
	
	public BlockFetcher(Segment segment, RetryTracker tracker, FreenetURI freenetURI, int index, boolean dontEnterImplicitArchives) {
		super(tracker, index, null);
		this.segment = segment;
		uri = freenetURI;
		completedTries = 0;
		fetchedData = null;
		actuallyFetched = false;
		this.dontEnterImplicitArchives = dontEnterImplicitArchives;
	}

	public String getName() {
		return "BlockFetcher for "+getNumber();
	}
	
	public void run() {
		Logger.minor(this, "Running: "+this);
		// Already added to runningFetches.
		// But need to make sure we are removed when we exit.
		try {
			realRun();
		} catch (Throwable t) {
			fatalError(t, FetchException.INTERNAL_ERROR);
		} finally {
			completedTries++;
		}
	}

	public String toString() {
		return super.toString()+" tries="+completedTries+" uri="+uri;
	}
	
	private void realRun() {
		// Do the fetch
		Fetcher f = new Fetcher(uri, this.segment.blockFetchContext);
		try {
			FetchResult fr = f.realRun(new ClientMetadata(), segment.recursionLevel, uri, 
					(!this.segment.nonFullBlocksAllowed) || dontEnterImplicitArchives, segment.blockFetchContext.localRequestOnly || completedTries == 0);
			actuallyFetched = true;
			fetchedData = fr.data;
			Logger.minor(this, "Fetched "+fetchedData.size()+" bytes on "+this);
			tracker.success(this);
		} catch (MetadataParseException e) {
			fatalError(e, FetchException.INVALID_METADATA);
		} catch (FetchException e) {
			int code = e.getMode();
			boolean isFatal = e.isFatal();
			if(isFatal)
				fatalError(e, code);
			else
				nonfatalError(e, code);
		} catch (ArchiveFailureException e) {
			fatalError(e, FetchException.ARCHIVE_FAILURE);
		} catch (ArchiveRestartException e) {
			Logger.error(this, "Got an ArchiveRestartException in a splitfile - WTF?");
			fatalError(e, FetchException.ARCHIVE_FAILURE);
		}
	}

	private void fatalError(Throwable e, int code) {
		Logger.error(this, "Giving up on block: "+this+": "+e, e);
		tracker.fatalError(this, code);
	}

	private void nonfatalError(Exception e, int code) {
		Logger.minor(this, "Non-fatal error on "+this+": "+e);
		tracker.nonfatalError(this, code);
	}
	
	public boolean succeeded() {
		return fetchedData != null;
	}

	/**
	 * Queue a healing block for insert.
	 * Will be implemented using the download manager.
	 * FIXME: implement!
	 */
	public void queueHeal() {
		// TODO Auto-generated method stub
		
	}

	public void kill() {
		// Do nothing, for now
	}

	public FreenetURI getURI() {
		return uri;
	}
	
	public void setData(Bucket data) {
		actuallyFetched = false;
		super.setData(data);
	}

	protected void checkStartable() {
		if(fetchedData != null) {
			throw new IllegalStateException("Already have data");
		}
	}

	public int getRetryCount() {
		return completedTries;
	}
}