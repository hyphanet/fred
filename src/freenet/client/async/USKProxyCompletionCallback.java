/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.List;

import com.db4o.ObjectContainer;

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
public class USKProxyCompletionCallback implements GetCompletionCallback {

	final USK usk;
	final GetCompletionCallback cb;
	final boolean persistent;
	
	public USKProxyCompletionCallback(USK usk, GetCompletionCallback cb, boolean persistent) {
		this.usk = usk;
		this.cb = cb;
		this.persistent = persistent;
	}

	@Override
	public void onSuccess(StreamGenerator streamGenerator, ClientMetadata clientMetadata, List<? extends Compressor> decompressors, ClientGetState state, ObjectContainer container, ClientContext context) {
		if(container != null && persistent) {
			container.activate(cb, 1);
			container.activate(usk, 5);
		}
		context.uskManager.updateKnownGood(usk, usk.suggestedEdition, context);
		cb.onSuccess(streamGenerator, clientMetadata, decompressors, state, container, context);
		if(persistent) removeFrom(container);
	}

	private void removeFrom(ObjectContainer container) {
		container.activate(usk, 5);
		usk.removeFrom(container);
		container.delete(this);
	}

	@Override
	public void onFailure(FetchException e, ClientGetState state, ObjectContainer container, ClientContext context) {
		switch(e.mode) {
		case FetchException.NOT_ENOUGH_PATH_COMPONENTS:
		case FetchException.PERMANENT_REDIRECT:
			context.uskManager.updateKnownGood(usk, usk.suggestedEdition, context);
		}
		if(persistent) {
			container.activate(cb, 1);
			container.activate(usk, 5);
		}
		FreenetURI uri = e.newURI;
		if(uri != null) {
			// FIXME what are we doing here anyway? Document!
			uri = usk.turnMySSKIntoUSK(uri);
			e = new FetchException(e, uri);
		}
		cb.onFailure(e, state, container, context);
		if(persistent) removeFrom(container);
	}

	@Override
	public void onBlockSetFinished(ClientGetState state, ObjectContainer container, ClientContext context) {
		if(container != null && persistent)
			container.activate(cb, 1);
		cb.onBlockSetFinished(state, container, context);
	}

	@Override
	public void onTransition(ClientGetState oldState, ClientGetState newState, ObjectContainer container) {
		// Ignore
	}

	@Override
	public void onExpectedMIME(ClientMetadata metadata, ObjectContainer container, ClientContext context) throws FetchException {
		if(container != null && persistent)
			container.activate(cb, 1);
		cb.onExpectedMIME(metadata, container, context);
	}

	@Override
	public void onExpectedSize(long size, ObjectContainer container, ClientContext context) {
		if(container != null && persistent)
			container.activate(cb, 1);
		cb.onExpectedSize(size, container, context);
	}

	@Override
	public void onFinalizedMetadata(ObjectContainer container) {
		if(container != null && persistent)
			container.activate(cb, 1);
		cb.onFinalizedMetadata(container);
	}

	@Override
	public void onExpectedTopSize(long size, long compressed, int blocksReq, int blocksTotal, ObjectContainer container, ClientContext context) {
		cb.onExpectedTopSize(size, compressed, blocksReq, blocksTotal, container, context);
	}

	@Override
	public void onSplitfileCompatibilityMode(CompatibilityMode min, CompatibilityMode max, byte[] splitfileKey, boolean dontCompress, boolean bottomLayer, boolean definitiveAnyway, ObjectContainer container, ClientContext context) {
		cb.onSplitfileCompatibilityMode(min, max, splitfileKey, dontCompress, bottomLayer, definitiveAnyway, container, context);
	}

	@Override
	public void onHashes(HashResult[] hashes, ObjectContainer container, ClientContext context) {
		cb.onHashes(hashes, container, context);
	}

}
