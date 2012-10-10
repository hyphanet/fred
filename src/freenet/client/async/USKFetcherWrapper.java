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
import freenet.node.RequestClient;
import freenet.support.compress.Compressor;

/**
 * Wrapper for a backgrounded USKFetcher.
 */
public class USKFetcherWrapper extends BaseClientGetter {

	final USK usk;
	
	public USKFetcherWrapper(USK usk, short prio, RequestClient client) {
		super(prio, client);
		this.usk = usk;
	}

	@Override
	public FreenetURI getURI() {
		return usk.getURI();
	}

	@Override
	public boolean isFinished() {
		return false;
	}

	@Override
	public void notifyClients(ObjectContainer container, ClientContext context) {
		// Do nothing
	}

	@Override
	public void onSuccess(StreamGenerator streamGenerator, ClientMetadata clientMetadata, List<? extends Compressor> decompressors, ClientGetState state, ObjectContainer container, ClientContext context) {
		// Ignore; we don't do anything with it because we are running in the background.
	}

	@Override
	public void onFailure(FetchException e, ClientGetState state, ObjectContainer container, ClientContext context) {
		// Ignore
	}

	@Override
	public void onBlockSetFinished(ClientGetState state, ObjectContainer container, ClientContext context) {
		// Ignore
	}

	@Override
	public void onTransition(ClientGetState oldState, ClientGetState newState, ObjectContainer container) {
		// Ignore
	}

	@Override
	public String toString() {
		return super.toString()+ ':' +usk;
	}

	@Override
	public void onExpectedMIME(ClientMetadata meta, ObjectContainer container, ClientContext context) {
		// Ignore
	}

	@Override
	public void onExpectedSize(long size, ObjectContainer container, ClientContext context) {
		// Ignore
	}

	@Override
	public void onFinalizedMetadata(ObjectContainer container) {
		// Ignore
	}

	@Override
	public void cancel(ObjectContainer container, ClientContext context) {
		super.cancel();
	}

	@Override
	protected void innerToNetwork(ObjectContainer container, ClientContext context) {
		// Ignore
	}

	@Override
	public void onExpectedTopSize(long size, long compressed, int blocksReq, int blocksTotal, ObjectContainer container, ClientContext context) {
		// Ignore
	}

	@Override
	public void onSplitfileCompatibilityMode(CompatibilityMode min, CompatibilityMode max, byte[] splitfileKey, boolean compressed, boolean bottomLayer, boolean definitiveAnyway, ObjectContainer container, ClientContext context) {
		// Ignore
	}

	@Override
	public void onHashes(HashResult[] hashes, ObjectContainer container, ClientContext context) {
		// Ignore
	}

}
