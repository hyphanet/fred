package freenet.client.async;

import freenet.client.ClientMetadata;
import freenet.client.FetchResult;
import freenet.support.api.Bucket;

public class CacheFetchResult extends FetchResult {
	
	public final boolean alreadyFiltered;

	public CacheFetchResult(ClientMetadata dm, Bucket fetched, boolean alreadyFiltered) {
		super(dm, fetched);
		this.alreadyFiltered = alreadyFiltered;
	}

}
