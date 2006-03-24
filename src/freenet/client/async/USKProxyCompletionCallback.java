package freenet.client.async;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.keys.USK;

public class USKProxyCompletionCallback implements GetCompletionCallback {

	final USK usk;
	final USKManager uskManager;
	final GetCompletionCallback cb;
	
	public USKProxyCompletionCallback(USK usk, USKManager um, GetCompletionCallback cb) {
		this.usk = usk;
		this.uskManager = um;
		this.cb = cb;
	}

	public void onSuccess(FetchResult result, ClientGetState state) {
		uskManager.update(usk, usk.suggestedEdition);
		cb.onSuccess(result, state);
	}

	public void onFailure(FetchException e, ClientGetState state) {
		cb.onFailure(e, state);
	}

	public void onBlockSetFinished(ClientGetState state) {
		cb.onBlockSetFinished(state);
	}

}
