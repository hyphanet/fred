package freenet.client;

import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientCallback;
import freenet.client.async.ClientGetter;
import freenet.keys.FreenetURI;

public class FetchWaiter implements ClientCallback {

	private FetchResult result;
	private FetchException error;
	private boolean finished;
	
	public synchronized void onSuccess(FetchResult result, ClientGetter state) {
		if(finished) return;
		this.result = result;
		finished = true;
		notifyAll();
	}

	public synchronized void onFailure(FetchException e, ClientGetter state) {
		if(finished) return;
		this.error = e;
		finished = true;
		notifyAll();
	}

	public void onSuccess(BaseClientPutter state) {
		throw new UnsupportedOperationException();
	}

	public void onFailure(InserterException e, BaseClientPutter state) {
		throw new UnsupportedOperationException();
	}

	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state) {
		throw new UnsupportedOperationException();
	}

	public synchronized FetchResult waitForCompletion() throws FetchException {
		while(!finished) {
			try {
				wait();
			} catch (InterruptedException e) {
				// Ignore
			}
		}

		if(error != null) throw error;
		return result;
	}

	public void onMajorProgress() {
		// Ignore
	}
}
