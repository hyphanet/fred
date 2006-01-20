package freenet.client.async;

import freenet.client.FetchException;
import freenet.client.FetchResult;

/**
 * A client process. Something that initiates requests, and can cancel
 * them. FCP, Fproxy, and the GlobalPersistentClient, implement this
 * somewhere.
 */
public interface Client {

	public void onSuccess(FetchResult result, ClientGet state);
	
	public void onFailure(FetchException e, ClientGet state);

}
