package freenet.node.fcp;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.FetcherContext;
import freenet.client.InserterException;
import freenet.client.async.ClientCallback;
import freenet.client.async.ClientGetter;
import freenet.client.async.ClientPutter;
import freenet.keys.FreenetURI;
import freenet.support.Logger;

/**
 * A simple client fetch. This can of course fetch arbitrarily large
 * files, including splitfiles, redirects, etc.
 */
public class ClientGet extends ClientRequest implements ClientCallback {

	private final FreenetURI uri;
	private final FetcherContext fctx;
	private final String identifier;
	private final int verbosity;
	private final FCPConnectionHandler handler;
	private final ClientGetter getter;
	private final short priorityClass;
	
	public ClientGet(FCPConnectionHandler handler, ClientGetMessage message) {
		uri = message.uri;
		// FIXME
		this.priorityClass = 0;
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
		getter = new ClientGetter(this, handler.node.fetchScheduler, uri, fctx, priorityClass);
		try {
			getter.start();
		} catch (FetchException e) {
			onFailure(e, null);
		}
	}

	public void cancel() {
		fctx.cancel();
	}

	public void onSuccess(FetchResult result, ClientGetter state) {
		FCPMessage msg = new DataFoundMessage(handler, result, identifier);
		handler.outputHandler.queue(msg);
		// Send all the data at once
		// FIXME there should be other options
		msg = new AllDataMessage(handler, result.asBucket(), identifier);
		handler.outputHandler.queue(msg);
	}

	public void onFailure(FetchException e, ClientGetter state) {
		Logger.minor(this, "Caught "+e, e);
		FCPMessage msg = new GetFailedMessage(handler, e, identifier);
		handler.outputHandler.queue(msg);
	}

	public void onSuccess(ClientPutter state) {
		// Ignore
	}

	public void onFailure(InserterException e, ClientPutter state) {
		// Ignore
	}

	public void onGeneratedURI(FreenetURI uri, ClientPutter state) {
		// Ignore
	}

}
