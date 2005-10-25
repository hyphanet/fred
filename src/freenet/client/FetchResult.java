package freenet.client;

import java.io.IOException;

import freenet.support.Bucket;
import freenet.support.BucketTools;

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
	 * @throws IOException If it was not possible to read the data.
	 */
	public byte[] asByteArray() throws IOException {
		return BucketTools.toByteArray(data);
	}
	
	/** Get the result as a Bucket */
	public Bucket asBucket() {
		return data;
	}
}
