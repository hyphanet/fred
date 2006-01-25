package freenet.client;

import freenet.client.async.ClientCallback;
import freenet.client.async.ClientGetter;
import freenet.client.async.ClientPutter;
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

	public void onSuccess(ClientPutter state) {
		throw new UnsupportedOperationException();
	}

	public void onFailure(InserterException e, ClientPutter state) {
		throw new UnsupportedOperationException();
	}

	public void onGeneratedURI(FreenetURI uri, ClientPutter state) {
		throw new UnsupportedOperationException();
	}

	public FetchResult waitForCompletion() throws FetchException {
		synchronized(this) {
			while(!finished) {
				try {
					wait();
				} catch (InterruptedException e) {
					// Ignore
				}
			}
		}
		if(error != null) throw error;
		return result;
	}
}
