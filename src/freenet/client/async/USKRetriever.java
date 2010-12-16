/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.IOException;
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
import freenet.node.PrioRunnable;
import freenet.node.RequestClient;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.api.Bucket;
import freenet.support.compress.Compressor;
import freenet.support.compress.DecompressorThreadManager;
import freenet.support.io.Closer;
import freenet.support.Logger.LogLevel;
import freenet.support.io.NativeThread;

/**
 * Poll a USK, and when a new slot is found, fetch it. 
 */
public class USKRetriever extends BaseClientGetter implements USKCallback {

	/** Context for fetching data */
	final FetchContext ctx;
	final USKRetrieverCallback cb;
	final USK origUSK;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

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
						ctx.maxNonSplitfileRetries, 0, true, l, true, false, null, context, realTimeFlag);
			getter.schedule(null, context);
		} catch (MalformedURLException e) {
			Logger.error(this, "Impossible: "+e, e);
		} catch (FetchException e) {
			Logger.error(this, "Could not start fetcher for "+uri+" : "+e, e);
		}
	}

	public void onSuccess(StreamGenerator streamGenerator, ClientMetadata clientMetadata, List<? extends Compressor> decompressors, final ClientGetState state, ObjectContainer container, ClientContext context) {
		if(logMINOR)
			Logger.minor(this, "Success on "+this+" from "+state+" : length "+streamGenerator.size()+"mime type "+clientMetadata.getMIMEType());
		DecompressorThreadManager decompressorManager = null;
		OutputStream output = null;
		Bucket finalResult = null;
		long maxLen = Math.max(ctx.maxTempLength, ctx.maxOutputLength);
		try {
			finalResult = context.getBucketFactory(persistent()).makeBucket(maxLen);
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
			output = finalResult.getOutputStream();
			// Decompress
			if(decompressors != null) {
				if(logMINOR) Logger.minor(this, "Decompressing...");
				if(persistent()) {
					container.activate(decompressors, 5);
					container.activate(ctx, 1);
				}
				pipeIn = new PipedInputStream();
				pipeOut = new PipedOutputStream(pipeIn);
				decompressorManager = new DecompressorThreadManager(pipeIn, decompressors, maxLen);
				pipeIn = decompressorManager.execute();
				ClientGetWorkerThread worker = new ClientGetWorkerThread(pipeIn, output, null, null, null, false, null, null, null);
				worker.start();
				streamGenerator.writeTo(pipeOut, container, context);
				worker.waitFinished();
				pipeOut.close();
			} else {
				try {
					streamGenerator.writeTo(output, container, context);
					output.close();
				} finally {
					Closer.close(output);
				}
			}
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
			Closer.close(output);
			Closer.close(pipeOut);
		}

		final FetchResult result = new FetchResult(clientMetadata, finalResult);
		context.uskManager.updateKnownGood(origUSK, state.getToken(), context);
		context.mainExecutor.execute(new PrioRunnable() {

			public void run() {
				cb.onFound(origUSK, state.getToken(), result);
			}

			public int getPriority() {
				return NativeThread.NORM_PRIORITY;
			}
			
		});
		
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
