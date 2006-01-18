package freenet.node.fcp;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.Fetcher;
import freenet.client.FetcherContext;
import freenet.keys.FreenetURI;
import freenet.support.Logger;

/**
 * A simple client fetch. This can of course fetch arbitrarily large
 * files, including splitfiles, redirects, etc.
 */
public class ClientGet extends ClientRequest implements Runnable {

	private final FreenetURI uri;
	private final FetcherContext fctx;
	private final Fetcher f;
	private final String identifier;
	private final int verbosity;
	private final FCPConnectionHandler handler;
	
	public ClientGet(FCPConnectionHandler handler, ClientGetMessage message) {
		uri = message.uri;
		// Create a Fetcher directly in order to get more fine-grained control,
		// since the client may override a few context elements.
		this.handler = handler;
		fctx = new FetcherContext(handler.defaultFetchContext, FetcherContext.IDENTICAL_MASK);
		// ignoreDS
		fctx.localRequestOnly = message.dsOnly;
		fctx.ignoreStore = message.ignoreDS;
		fctx.maxNonSplitfileRetries = message.maxRetries;
		fctx.maxSplitfileBlockRetries = Math.max(fctx.maxSplitfileBlockRetries, message.maxRetries);
		this.identifier = message.identifier;
		this.verbosity = message.verbosity;
		// FIXME do something with verbosity !!
		// Has already been checked
		if(message.returnType != ClientGetMessage.RETURN_TYPE_DIRECT)
			throw new IllegalStateException("Unknown return type: "+message.returnType);
		fctx.maxOutputLength = message.maxSize;
		fctx.maxTempLength = message.maxTempSize;
		f = new Fetcher(uri, fctx);
		Thread t = new Thread(this, "FCP fetcher for "+uri+" ("+identifier+") on "+handler);
		t.setDaemon(true);
		t.start();
	}

	public void cancel() {
		fctx.cancel();
	}

	public void run() {
		try {
			FetchResult fr = f.run();
			// Success!!!
			FCPMessage msg = new DataFoundMessage(handler, fr, identifier);
			handler.outputHandler.queue(msg);
			// Send all the data at once
			// FIXME there should be other options
			msg = new AllDataMessage(handler, fr.asBucket(), identifier);
			handler.outputHandler.queue(msg);
		} catch (FetchException e) {
			// Error
			Logger.minor(this, "Caught "+e, e);
			FCPMessage msg = new FetchErrorMessage(handler, e, identifier);
			handler.outputHandler.queue(msg);
		}
	}

}
