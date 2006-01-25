package freenet.client.async;

import freenet.client.FetchException;
import freenet.client.FetchResult;

/**
 * Callback called when part of a get request completes - either with a 
 * Bucket full of data, or with an error.
 */
public interface GetCompletionCallback {

	public void onSuccess(FetchResult result, ClientGetState state);
	
	public void onFailure(FetchException e, ClientGetState state);
	
}
