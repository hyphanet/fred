/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import java.io.IOException;

import freenet.support.io.Bucket;
import freenet.support.io.BucketTools;

/**
 * Class to contain the result of a key fetch.
 */
public class FetchResult {

	final ClientMetadata metadata;
	final Bucket data;
	
	public FetchResult(ClientMetadata dm, Bucket fetched) {
		metadata = dm;
		data = fetched;
	}

	/**
	 * Create a FetchResult with a new Bucket of data, but everything else
	 * the same as the old one.
	 */
	public FetchResult(FetchResult fr, Bucket output) {
		this.data = output;
		this.metadata = fr.metadata;
	}

	/** Get the MIME type of the fetched data. 
	 * If unknown, returns application/octet-stream. */
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
