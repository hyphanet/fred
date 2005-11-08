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
	 * @throws FetchException If there is an error fetching the data
	 */
	public FetchResult fetch(FreenetURI uri) throws FetchException;

	/**
	 * Blocking insert of a URI
	 * @throws InserterException If there is an error inserting the data
	 */
	public FreenetURI insert(InsertBlock insert) throws InserterException;
}
