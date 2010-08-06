/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.MalformedURLException;
import java.util.List;

import com.db4o.ObjectContainer;

import freenet.client.ArchiveContext;
import freenet.client.ClientMetadata;
import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.InsertContext.CompatibilityMode;
import freenet.crypt.HashResult;
import freenet.keys.FreenetURI;
import freenet.keys.USK;
import freenet.node.RequestClient;
import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.api.Bucket;
import freenet.support.compress.Compressor;
import freenet.support.compress.DecompressorThreadManager;
import freenet.support.io.Closer;
import freenet.support.io.FileUtil;
import freenet.support.Logger.LogLevel;

/**
 * Poll a USK, and when a new slot is found, fetch it. 
 */
public class USKRetriever extends BaseClientGetter implements USKCallback {

	/** Context for fetching data */
	final FetchContext ctx;
	final USKRetrieverCallback cb;
	final USK origUSK;
	
	public USKRetriever(FetchContext fctx, short prio,  
			RequestClient client, USKRetrieverCallback cb, USK origUSK) {
		super(prio, client);
		if(client.persistent()) throw new UnsupportedOperationException("USKRetriever cannot be persistent");
		this.ctx = fctx;
		this.cb = cb;
		this.origUSK = origUSK;
	}

	public void onFoundEdition(long l, USK key, ObjectContainer container, ClientContext context, boolean metadata, short codec, byte[] data, boolean newKnownGood, boolean newSlotToo) {
		if(l < 0) {
			Logger.error(this, "Found negative edition: "+l+" for "+key+" !!!");
			return;
		}
		if(l < origUSK.suggestedEdition) {
			Logger.warning(this, "Found edition prior to that specified by the client: "+l+" < "+origUSK.suggestedEdition, new Exception("error"));
			return;
		}
		// Create a SingleFileFetcher for the key (as an SSK).
		// Put the edition number into its context object.
		// Put ourself as callback.
		// Fetch it. If it fails, ignore it, if it succeeds, return the data with the edition # to the client.
		FreenetURI uri = key.getSSK(l).getURI();
		try {
			SingleFileFetcher getter =
				(SingleFileFetcher) SingleFileFetcher.create(this, this, uri, ctx, new ArchiveContext(ctx.maxTempLength, ctx.maxArchiveLevels), 
						ctx.maxNonSplitfileRetries, 0, true, l, true, null, false, null, context);
			getter.schedule(null, context);
		} catch (MalformedURLException e) {
			Logger.error(this, "Impossible: "+e, e);
		} catch (FetchException e) {
			Logger.error(this, "Could not start fetcher for "+uri+" : "+e, e);
		}
	}

	public void onSuccess(StreamGenerator streamGenerator, ClientMetadata clientMetadata, List<? extends Compressor> decompressors, ClientGetState state, ObjectContainer container, ClientContext context) {
		if(Logger.shouldLog(LogLevel.MINOR, this))
			Logger.minor(this, "Success on "+this+" from "+state+" :"+" mime type "+clientMetadata.getMIMEType());
		DecompressorThreadManager decompressorManager = null;
		OutputStream output = null;
		InputStream input = null;
		Bucket finalResult = null;
		long maxLen = Math.max(ctx.maxTempLength, ctx.maxOutputLength);
		try {
			finalResult = context.getBucketFactory(persistent()).makeBucket(maxLen);
			output = finalResult.getOutputStream();
		} catch (IOException e) {
			Logger.error(this, "Caught "+e, e);
			onFailure(new FetchException(FetchException.BUCKET_ERROR, e), state, container, context);
			return;
		} catch(Throwable t) {
			Logger.error(this, "Caught "+t, t);
			onFailure(new FetchException(FetchException.INTERNAL_ERROR, t), state, container, context);
			return;
		}

		PipedInputStream pipeIn = null;
		PipedOutputStream pipeOut = null;
		try {
			// Decompress
			if(decompressors != null) {
				if(Logger.shouldLog(LogLevel.MINOR, this)) Logger.minor(this, "Decompressing...");
				if(persistent()) {
					container.activate(decompressors, 5);
					container.activate(ctx, 1);
				}
				pipeIn = new PipedInputStream();
				pipeOut = new PipedOutputStream(pipeIn);
				decompressorManager = new DecompressorThreadManager(pipeIn, decompressors, maxLen);
				input = decompressorManager.execute();
				final InputStream finalInput = input;
				final OutputStream finalOutput = output;
				final ClientGetState finalState = state;
				final ObjectContainer finalContainer = container;
				final ClientContext finalContext = context;
				new Thread() {
					public void run() {
						try {
							FileUtil.copy(finalInput, finalOutput, -1);
						} catch(IOException e) {
							onFailure(new FetchException(FetchException.BUCKET_ERROR, e), finalState, finalContainer, finalContext);
						}
					}
				}.start();
				streamGenerator.writeTo(pipeOut, container, context);
				pipeOut.close();
			} else streamGenerator.writeTo(output, container, context);
			input.close();
			output.close();
		} catch (OutOfMemoryError e) {
			OOMHandler.handleOOM(e);
			System.err.println("Failing above attempted fetch...");
			onFailure(new FetchException(FetchException.INTERNAL_ERROR, e), state, container, context);
			return;
		} catch(IOException e) {
			Logger.error(this, "Caught "+e, e);
			onFailure(new FetchException(FetchException.INTERNAL_ERROR, e), state, container, context);
		} catch (Throwable t) {
			Logger.error(this, "Caught "+t, t);
			onFailure(new FetchException(FetchException.INTERNAL_ERROR, t), state, container, context);
			return;
		} finally {
			Closer.close(input);
			Closer.close(output);
			Closer.close(pipeOut);
		}

		FetchResult result = new FetchResult(clientMetadata, finalResult);
		cb.onFound(origUSK, state.getToken(), result);
		context.uskManager.updateKnownGood(origUSK, state.getToken(), context);
	}

	public void onFailure(FetchException e, ClientGetState state, ObjectContainer container, ClientContext context) {
		switch(e.mode) {
		case FetchException.NOT_ENOUGH_PATH_COMPONENTS:
		case FetchException.PERMANENT_REDIRECT:
			context.uskManager.updateKnownGood(origUSK, state.getToken(), context);
			return;
		}
		Logger.warning(this, "Found edition "+state.getToken()+" but failed to fetch edition: "+e, e);
	}

	public void onBlockSetFinished(ClientGetState state, ObjectContainer container, ClientContext context) {
		// Ignore
	}

	/**
	 * Get the original USK URI which was passed when creating the retriever - not the latest known URI!
	 */
	public USK getOriginalUSK() {
		return origUSK;
	}

	/**
	 * Get the original USK URI which was passed when creating the retriever - not the latest known URI!
	 */
	@Override
	public FreenetURI getURI() {
		// FIXME: Toad: Why did getURI() return null? Does it break anything that I made it return the URI?
		return origUSK.getURI();
	}

	@Override
	public boolean isFinished() {
		return false;
	}

	@Override
	public void notifyClients(ObjectContainer container, ClientContext context) {
		// Ignore for now
	}

	@Override
	public void onTransition(ClientGetState oldState, ClientGetState newState, ObjectContainer container) {
		// Ignore
	}

	public void onExpectedMIME(String mime, ObjectContainer container, ClientContext context) {
		// Ignore
	}

	public void onExpectedSize(long size, ObjectContainer container, ClientContext context) {
		// Ignore
	}

	public void onFinalizedMetadata(ObjectContainer container) {
		// Ignore
	}

	public short getPollingPriorityNormal() {
		return cb.getPollingPriorityNormal();
	}

	public short getPollingPriorityProgress() {
		return cb.getPollingPriorityProgress();
	}

	@Override
	public void cancel(ObjectContainer container, ClientContext context) {
		super.cancel();
	}

	@Override
	protected void innerToNetwork(ObjectContainer container, ClientContext context) {
		// Ignore
	}

	public void onExpectedTopSize(long size, long compressed, int blocksReq, int blocksTotal, ObjectContainer container, ClientContext context) {
		// Ignore
	}

	public void onSplitfileCompatibilityMode(CompatibilityMode min, CompatibilityMode max, byte[] splitfileKey, boolean compressed, boolean bottomLayer, boolean definitiveAnyway, ObjectContainer container, ClientContext context) {
		// Ignore
	}
	
	public void onHashes(HashResult[] hashes, ObjectContainer container, ClientContext context) {
		// Ignore
	}

}
