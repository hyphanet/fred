package freenet.client;

import freenet.support.api.Bucket;

public class TempFetchResult extends FetchResult {

	public final boolean freeWhenDone;
	
	public TempFetchResult(ClientMetadata dm, Bucket fetched, boolean freeWhenDone) {
		super(dm, fetched);
		this.freeWhenDone = freeWhenDone;
	}
	
	/**
	 * If true, the recipient of this object is responsible for freeing the data.
	 * If false, it is a reference to data held somewhere else, so doesn't need to be freed. 
	 * @return
	 */
	public boolean freeWhenDone() {
		return freeWhenDone;
	}

}
