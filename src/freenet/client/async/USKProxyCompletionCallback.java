/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.Serializable;
import java.util.List;

import freenet.client.ClientMetadata;
import freenet.client.FetchException;
import freenet.client.InsertContext.CompatibilityMode;
import freenet.crypt.HashResult;
import freenet.keys.FreenetURI;
import freenet.keys.USK;
import freenet.support.compress.Compressor;

/**
 * Passes everything through, except that is updates the lastKnownGood on the USKManager,
 * and has some code to handle new URIs.
 * @author toad
 *
 */
public class USKProxyCompletionCallback implements GetCompletionCallback, Serializable {

    private static final long serialVersionUID = 1L;
    final USK usk;
	final GetCompletionCallback cb;
	final boolean persistent;
	
	public USKProxyCompletionCallback(USK usk, GetCompletionCallback cb, boolean persistent) {
		this.usk = usk;
		this.cb = cb;
		this.persistent = persistent;
	}

	@Override
	public void onSuccess(StreamGenerator streamGenerator, ClientMetadata clientMetadata, List<? extends Compressor> decompressors, ClientGetState state, ClientContext context) {
		context.uskManager.updateKnownGood(usk, usk.suggestedEdition, context);
		cb.onSuccess(streamGenerator, clientMetadata, decompressors, state, context);
	}

	@Override
	public void onFailure(FetchException e, ClientGetState state, ClientContext context) {
		switch(e.mode) {
		case NOT_ENOUGH_PATH_COMPONENTS:
		case PERMANENT_REDIRECT:
			context.uskManager.updateKnownGood(usk, usk.suggestedEdition, context);
		}
		FreenetURI uri = e.newURI;
		if(uri != null) {
			// FIXME what are we doing here anyway? Document!
			uri = usk.turnMySSKIntoUSK(uri);
			e = new FetchException(e, uri);
		}
		cb.onFailure(e, state, context);
	}

	@Override
	public void onBlockSetFinished(ClientGetState state, ClientContext context) {
		cb.onBlockSetFinished(state, context);
	}

	@Override
	public void onTransition(ClientGetState oldState, ClientGetState newState, ClientContext context) {
		// Ignore
	}

	@Override
	public void onExpectedMIME(ClientMetadata metadata, ClientContext context) throws FetchException {
		cb.onExpectedMIME(metadata, context);
	}

	@Override
	public void onExpectedSize(long size, ClientContext context) {
		cb.onExpectedSize(size, context);
	}

	@Override
	public void onFinalizedMetadata() {
		cb.onFinalizedMetadata();
	}

	@Override
	public void onExpectedTopSize(long size, long compressed, int blocksReq, int blocksTotal, ClientContext context) {
		cb.onExpectedTopSize(size, compressed, blocksReq, blocksTotal, context);
	}

	@Override
	public void onSplitfileCompatibilityMode(CompatibilityMode min, CompatibilityMode max, byte[] splitfileKey, boolean dontCompress, boolean bottomLayer, boolean definitiveAnyway, ClientContext context) {
		cb.onSplitfileCompatibilityMode(min, max, splitfileKey, dontCompress, bottomLayer, definitiveAnyway, context);
	}

	@Override
	public void onHashes(HashResult[] hashes, ClientContext context) {
		cb.onHashes(hashes, context);
	}

}
