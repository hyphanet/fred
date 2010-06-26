/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.InputStream;
import java.util.List;

import com.db4o.ObjectContainer;

import freenet.client.ClientMetadata;
import freenet.client.FetchException;
import freenet.support.compress.Compressor;
import freenet.client.InsertContext.CompatibilityMode;
import freenet.crypt.HashResult;

/**
 * Callback called when part of a get request completes - either with a 
 * Bucket full of data, or with an error.
 */
public interface GetCompletionCallback {

	public void onSuccess(InputStream input, ClientMetadata clientMetadata, List<? extends Compressor> decompressors, ClientGetState state, ObjectContainer container, ClientContext context);
	
	public void onFailure(FetchException e, ClientGetState state, ObjectContainer container, ClientContext context);
	
	/** Called when the ClientGetState knows that it knows about
	 * all the blocks it will need to fetch.
	 */
	public void onBlockSetFinished(ClientGetState state, ObjectContainer container, ClientContext context);

	public void onTransition(ClientGetState oldState, ClientGetState newState, ObjectContainer container);

	public void onExpectedSize(long size, ObjectContainer container, ClientContext context);
	
	public void onExpectedMIME(String mime, ObjectContainer container, ClientContext context) throws FetchException;
	
	public void onFinalizedMetadata(ObjectContainer container);
	
	public void onExpectedTopSize(long size, long compressed, int blocksReq, int blocksTotal, ObjectContainer container, ClientContext context);
	
	public void onSplitfileCompatibilityMode(CompatibilityMode min, CompatibilityMode max, byte[] customSplitfileKey, boolean compressed, boolean bottomLayer, boolean definitiveAnyway, ObjectContainer container, ClientContext context);

	public void onHashes(HashResult[] hashes, ObjectContainer container, ClientContext context);
}
