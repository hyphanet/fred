package freenet.client.async;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.InserterException;

/**
 * A client process. Something that initiates requests, and can cancel
 * them. FCP, Fproxy, and the GlobalPersistentClient, implement this
 * somewhere.
 */
public interface Client {

	public void onSuccess(FetchResult result, ClientGet state);
	
	public void onFailure(FetchException e, ClientGet state);

	public void onSuccess(ClientPut state);
	
	public void onFailure(InserterException e, ClientPut state);
	
}
