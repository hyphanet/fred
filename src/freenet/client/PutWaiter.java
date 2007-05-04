package freenet.client;

import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientCallback;
import freenet.client.async.ClientGetter;
import freenet.keys.FreenetURI;
import freenet.support.Logger;

public class PutWaiter implements ClientCallback {

	private boolean finished;
	private boolean succeeded;
	private FreenetURI uri;
	private InsertException error;
	
	public void onSuccess(FetchResult result, ClientGetter state) {
		// Ignore
	}

	public void onFailure(FetchException e, ClientGetter state) {
		// Ignore
	}

	public synchronized void onSuccess(BaseClientPutter state) {
		succeeded = true;
		finished = true;
		notifyAll();
	}

	public synchronized void onFailure(InsertException e, BaseClientPutter state) {
		error = e;
		finished = true;
		notifyAll();
	}

	public synchronized void onGeneratedURI(FreenetURI uri, BaseClientPutter state) {
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "URI: "+uri);
		if(this.uri == null)
			this.uri = uri;
		if(uri.equals(this.uri)) return;
		Logger.error(this, "URI already set: "+this.uri+" but new URI: "+uri, new Exception("error"));
	}

	public synchronized FreenetURI waitForCompletion() throws InsertException {
		while(!finished) {
			try {
				wait();
			} catch (InterruptedException e) {
				// Ignore
			}
		}
		if(error != null) {
			error.uri = uri;
			throw error;
		}
		if(succeeded) return uri;
		Logger.error(this, "Did not succeed but no error");
		throw new InsertException(InsertException.INTERNAL_ERROR, "Did not succeed but no error", uri);
	}

	public void onMajorProgress() {
		// Ignore
	}

	public void onFetchable(BaseClientPutter state) {
		// Ignore
	}

}
