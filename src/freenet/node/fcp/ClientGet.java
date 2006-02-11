package freenet.node.fcp;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.FetcherContext;
import freenet.client.InserterException;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientCallback;
import freenet.client.async.ClientGetter;
import freenet.client.events.ClientEvent;
import freenet.client.events.ClientEventListener;
import freenet.client.events.SplitfileProgressEvent;
import freenet.keys.FreenetURI;
import freenet.support.Bucket;
import freenet.support.BucketTools;
import freenet.support.Logger;

/**
 * A simple client fetch. This can of course fetch arbitrarily large
 * files, including splitfiles, redirects, etc.
 */
public class ClientGet extends ClientRequest implements ClientCallback, ClientEventListener {

	private final FreenetURI uri;
	private final FetcherContext fctx;
	private final String identifier;
	private final int verbosity;
	private final FCPConnectionHandler handler;
	private final ClientGetter getter;
	private final short priorityClass;
	private final short returnType;
	private boolean finished;
	private final File targetFile;
	private final File tempFile;
	
	// Verbosity bitmasks
	private int VERBOSITY_SPLITFILE_PROGRESS = 1;
	
	public ClientGet(FCPConnectionHandler handler, ClientGetMessage message) {
		uri = message.uri;
		// FIXME
		this.priorityClass = message.priorityClass;
		// Create a Fetcher directly in order to get more fine-grained control,
		// since the client may override a few context elements.
		this.handler = handler;
		fctx = new FetcherContext(handler.defaultFetchContext, FetcherContext.IDENTICAL_MASK);
		fctx.eventProducer.addEventListener(this);
		// ignoreDS
		fctx.localRequestOnly = message.dsOnly;
		fctx.ignoreStore = message.ignoreDS;
		fctx.maxNonSplitfileRetries = message.maxRetries;
		fctx.maxSplitfileBlockRetries = Math.max(fctx.maxSplitfileBlockRetries, message.maxRetries);
		this.identifier = message.identifier;
		this.verbosity = message.verbosity;
		// FIXME do something with verbosity !!
		// Has already been checked
		this.returnType = message.returnType;
		fctx.maxOutputLength = message.maxSize;
		fctx.maxTempLength = message.maxTempSize;
		this.targetFile = message.diskFile;
		this.tempFile = message.tempFile;
		getter = new ClientGetter(this, handler.node.fetchScheduler, uri, fctx, priorityClass, handler);
	}

	void start() {
		try {
			getter.start();
		} catch (FetchException e) {
			onFailure(e, null);
		}
	}
	
	public void cancel() {
		getter.cancel();
	}

	public void onSuccess(FetchResult result, ClientGetter state) {
		finished = true;
		FCPMessage msg = new DataFoundMessage(handler, result, identifier);
		Bucket data = result.asBucket();
		if(returnType == ClientGetMessage.RETURN_TYPE_DIRECT) {
			// Send all the data at once
			// FIXME there should be other options
			handler.outputHandler.queue(msg);
			msg = new AllDataMessage(handler, data, identifier);
			handler.outputHandler.queue(msg);
			return; // don't delete the bucket yet
		} else if(returnType == ClientGetMessage.RETURN_TYPE_NONE) {
			// Do nothing
			handler.outputHandler.queue(msg);
			data.free();
			return;
		} else if(returnType == ClientGetMessage.RETURN_TYPE_DISK) {
			// Write to temp file, then rename over filename
			FileOutputStream fos;
			try {
				fos = new FileOutputStream(tempFile);
			} catch (FileNotFoundException e) {
				ProtocolErrorMessage pm = new ProtocolErrorMessage(ProtocolErrorMessage.COULD_NOT_WRITE_FILE, false, null, identifier);
				handler.outputHandler.queue(pm);
				data.free();
				return;
			}
			try {
				BucketTools.copyTo(data, fos, data.size());
			} catch (IOException e) {
				ProtocolErrorMessage pm = new ProtocolErrorMessage(ProtocolErrorMessage.COULD_NOT_WRITE_FILE, false, null, identifier);
				handler.outputHandler.queue(pm);
				data.free();
				try {
					fos.close();
				} catch (IOException e1) {
					// Ignore
				}
				return;
			}
			try {
				fos.close();
			} catch (IOException e) {
				Logger.error(this, "Caught "+e+" closing file "+tempFile, e);
			}
			if(!tempFile.renameTo(targetFile)) {
				ProtocolErrorMessage pm = new ProtocolErrorMessage(ProtocolErrorMessage.COULD_NOT_RENAME_FILE, false, null, identifier);
				handler.outputHandler.queue(pm);
				// Don't delete temp file, user might want it.
			}
			data.free();
			handler.outputHandler.queue(msg);
			return;
		}
	}

	public void onFailure(FetchException e, ClientGetter state) {
		finished = true;
		Logger.minor(this, "Caught "+e, e);
		FCPMessage msg = new GetFailedMessage(handler, e, identifier);
		handler.outputHandler.queue(msg);
	}

	public void onSuccess(BaseClientPutter state) {
		// Ignore
	}

	public void onFailure(InserterException e, BaseClientPutter state) {
		// Ignore
	}

	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state) {
		// Ignore
	}

	public void receive(ClientEvent ce) {
		if(finished) return;
		if(!(((verbosity & VERBOSITY_SPLITFILE_PROGRESS) == VERBOSITY_SPLITFILE_PROGRESS) &&
				(ce instanceof SplitfileProgressEvent)))
			return;
		SimpleProgressMessage progress = 
			new SimpleProgressMessage(identifier, (SplitfileProgressEvent)ce);
		handler.outputHandler.queue(progress);
	}

}
