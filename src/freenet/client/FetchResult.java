/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import java.io.IOException;

import freenet.support.api.Bucket;
import freenet.support.io.BucketTools;

/**
 * Class to contain the result of a key fetch.
 */
public class FetchResult {

	final ClientMetadata metadata;
	final Bucket data;
	/**If the ContentFilter finds the MIME and charset to be incorrect,
	 * this value should contain the valid setting.
	 * @param overrideMIME TODO
	 */
	final String overrideMIME;
	
	public FetchResult(ClientMetadata dm, Bucket fetched, String overrideMIME) {
		metadata = dm;
		data = fetched;
		this.overrideMIME = overrideMIME;
	}

	/**
	 * Create a FetchResult with a new Bucket of data, but everything else
	 * the same as the old one.
	 * @param overrideMIME TODO
	 */
	public FetchResult(FetchResult fr, Bucket output, String overrideMIME) {
		this.data = output == null ? fr.data : output;
		this.metadata = fr.metadata;
		this.overrideMIME = overrideMIME == null ? fr.overrideMIME : overrideMIME;
	}

	/** Get the MIME type of the fetched data.
	 * If the filter has overridden the MIME type,
	 * if it has detected a new charset, for example,
	 * this will be returned.
	 * If unknown, returns application/octet-stream. */
	public String getMimeType() {
		return overrideMIME == null ? metadata.getMIMEType() : overrideMIME;
	}

	/** Get the client-level metadata. */
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
	
	/**
	 * Get the result as a Bucket.
	 * 
	 * You have to call Closer.close(bucket) to free() the obtained Bucket to prevent resource leakage!
	 */
	public Bucket asBucket() {
		return data;
	}
}
