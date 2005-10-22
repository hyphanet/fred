package freenet.client;

import freenet.keys.FreenetURI;

public interface HighLevelSimpleClient {

	/**
	 * Set the maximum length of the fetched data.
	 */
	public void setMaxLength(long maxLength);
	
	/**
	 * Set the maximum length of any intermediate data, e.g. ZIP manifests.
	 */
	public void setMaxIntermediateLength(long maxIntermediateLength);

	/**
	 * Blocking fetch of a URI
	 */
	public FetchResult fetch(FreenetURI uri);

	/**
	 * Blocking insert of a URI
	 */
	public FreenetURI insert(InsertBlock insert);
}
