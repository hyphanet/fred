package freenet.client;

import freenet.keys.FreenetURI;
import freenet.node.SimpleLowLevelClient;
import freenet.support.BucketFactory;

public class HighLevelSimpleClientImpl implements HighLevelSimpleClient {

	private final SimpleLowLevelClient client;
	private final ArchiveManager archiveManager;
	private final BucketFactory bucketFactory;
	private long curMaxLength;
	private long curMaxTempLength;
	static final int MAX_RECURSION = 10;
	static final int MAX_ARCHIVE_RESTARTS = 2;
	
	public HighLevelSimpleClientImpl(SimpleLowLevelClient client, ArchiveManager mgr, BucketFactory bf) {
		this.client = client;
		archiveManager = mgr;
		bucketFactory = bf;
	}
	
	public void setMaxLength(long maxLength) {
		curMaxLength = maxLength;
	}

	public void setMaxIntermediateLength(long maxIntermediateLength) {
		curMaxTempLength = maxIntermediateLength;
	}

	public FetchResult fetch(FreenetURI uri) throws FetchException {
		FetcherContext context = new FetcherContext(client, curMaxLength, curMaxLength, 
				MAX_RECURSION, MAX_ARCHIVE_RESTARTS, archiveManager, bucketFactory);
		Fetcher f = new Fetcher(uri, context, new ArchiveContext());
		return f.run();
	}

	public FreenetURI insert(InsertBlock insert) {
		// TODO Auto-generated method stub
		return null;
	}

}
