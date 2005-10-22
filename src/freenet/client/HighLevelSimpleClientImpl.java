package freenet.client;

import freenet.keys.FreenetURI;
import freenet.node.SimpleLowLevelClient;

public class HighLevelSimpleClientImpl implements HighLevelSimpleClient {

	private SimpleLowLevelClient client;
	private long curMaxLength;
	private long curMaxTempLength;
	
	public HighLevelSimpleClientImpl(SimpleLowLevelClient client) {
		this.client = client;
	}
	
	public void setMaxLength(long maxLength) {
		curMaxLength = maxLength;
	}

	public void setMaxIntermediateLength(long maxIntermediateLength) {
		curMaxTempLength = maxIntermediateLength;
	}

	public FetchResult fetch(FreenetURI uri) {
		Fetcher f = new Fetcher(uri, client, curMaxLength, curMaxTempLength);
		return f.run(0);
	}

	public FreenetURI insert(InsertBlock insert) {
		// TODO Auto-generated method stub
		return null;
	}

}
