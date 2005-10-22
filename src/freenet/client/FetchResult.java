package freenet.client;

import freenet.support.Bucket;

/**
 * Class to contain the result of a key fetch.
 */
public class FetchResult {

	final boolean succeeded;
	final ClientMetadata metadata;
	final Bucket data;
	
	public FetchResult(ClientMetadata dm, Bucket fetched) {
		metadata = dm;
		data = fetched;
		succeeded = true;
	}

	/** Did it succeed? */
	public boolean succeeded() {
		return succeeded;
	}
	
	/** If so, get the MIME type */
	public String getMimeType() {
		return metadata.getMIMEType();
	}

	public ClientMetadata getMetadata() {
		return metadata;
	}

	/** @return The size of the data fetched, in bytes. */
	public long size() {
		return data.size();
	}
	
	/** Get the result as a simple byte array, even if we don't have it
	 * as one. @throws OutOfMemoryError !!
	 */
	public byte[] asByteArray() {
		return data.toByteArray();
	}
	
	/** Get the result as a Bucket */
	public Bucket asBucket() {
		return data;
	}
}
