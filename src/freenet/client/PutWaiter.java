package freenet.client;

import freenet.client.InsertException.InsertExceptionMode;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientContext;
import freenet.client.async.ClientPutCallback;
import freenet.keys.FreenetURI;
import freenet.node.RequestClient;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;

/** Provides a blocking wrapper for an insert. Used for simple blocking APIs such as HighLevelSimpleClient. */
public class PutWaiter implements ClientPutCallback {

	private boolean finished;
	private boolean succeeded;
	private FreenetURI uri;
	private InsertException error;
	final RequestClient client;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	public PutWaiter(RequestClient client) {
	    this.client = client;
    }

    @Override
	public synchronized void onSuccess(BaseClientPutter state) {
		succeeded = true;
		finished = true;
		notifyAll();
	}

	@Override
	public synchronized void onFailure(InsertException e, BaseClientPutter state) {
		error = e;
		finished = true;
		notifyAll();
	}

	@Override
	public synchronized void onGeneratedURI(FreenetURI uri, BaseClientPutter state) {
		if(logMINOR)
			Logger.minor(this, "URI: "+uri);
		if(this.uri == null)
			this.uri = uri;
		if(uri.equals(this.uri)) return;
		Logger.error(this, "URI already set: "+this.uri+" but new URI: "+uri, new Exception("error"));
	}

	/** Waits for the insert to finish, returns the URI generated, throws if it failed. */
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
		throw new InsertException(InsertExceptionMode.INTERNAL_ERROR, "Did not succeed but no error", uri);
	}

	@Override
	public void onFetchable(BaseClientPutter state) {
		// Ignore
	}

	@Override
	public void onGeneratedMetadata(Bucket metadata, BaseClientPutter state) {
		Logger.error(this, "onGeneratedMetadata() on PutWaiter from "+state, new Exception("error"));
		metadata.free();
	}

    @Override
    public void onResume(ClientContext context) {
        throw new UnsupportedOperationException(); // Not persistent.
    }

    @Override
    public RequestClient getRequestClient() {
        return client;
    }

}
