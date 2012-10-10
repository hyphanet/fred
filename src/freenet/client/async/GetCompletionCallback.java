/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

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

	public void onSuccess(StreamGenerator streamGenerator, ClientMetadata clientMetadata, List<? extends Compressor> decompressors, ClientGetState state, ObjectContainer container, ClientContext context);
	
	public void onFailure(FetchException e, ClientGetState state, ObjectContainer container, ClientContext context);
	
	/** Called when the ClientGetState knows that it knows about
	 * all the blocks it will need to fetch.
	 */
	public void onBlockSetFinished(ClientGetState state, ObjectContainer container, ClientContext context);

	/** Called when the ClientGetState handling the request yields control to another 
	 * ClientGetState.
	 * @param oldState The old ClientGetState.
	 * @param newState The new ClientGetState.
	 * @param container The database handle. Must not be used by other threads.
	 */
	public void onTransition(ClientGetState oldState, ClientGetState newState, ObjectContainer container);

	/** Called when we know the size of the final data. Not the same as onExpectedTopSize(),
	 * which is called for new metadata and gives more information. This might be called 
	 * much later on for older content.
	 * @param size The expected size of the final data.
	 * @param container The database handle. Must not be used by other threads.
	 * @param context Utility object containing helpers, mostly not persistent, such as the Ticker, temporary storage factories etc.
	 */
	public void onExpectedSize(long size, ObjectContainer container, ClientContext context);

	/**
	 * Called when we know the MIME type of the final data. Useful for e.g. determining whether it
	 * is safe to handle etc, although the client can ask for the client layer to handle filtering.
	 * @param mime The MIME type, possibly including parameters, as a String. 
	 * E.g. "text/html; charset=ISO-8859-1".
	 * @param container The database handle. Must not be used by other threads.
	 * @param context Utility object containing helpers, mostly not persistent, such as the Ticker, temporary storage factories etc.
	 * @throws FetchException The callee can throw a FetchException to terminate the download e.g.
	 * if they can't handle the MIME type.
	 */
	public void onExpectedMIME(ClientMetadata metadata, ObjectContainer container, ClientContext context) throws FetchException;

	public void onFinalizedMetadata(ObjectContainer container);

	/**
	 * Called when we know the size of the final file, and the number of blocks needed etc. For 
	 * recent metadata, this is known at the time of handling the top block.
	 * @param size The final size of the data.
	 * @param compressed The size of the data after compression / before decompression.
	 * @param blocksReq The number of blocks needed to decode the file.
	 * @param blocksTotal The total number of blocks available.
	 * @param container The database handle. Must not be used by other threads.
	 * @param context Utility object containing helpers, mostly not persistent, such as the Ticker, temporary storage factories etc.
	 */
	public void onExpectedTopSize(long size, long compressed, int blocksReq, int blocksTotal, ObjectContainer container, ClientContext context);
	
	/**
	 * Called when we know the settings for the splitfile.
	 * @param min The lowest CompatibilityMode that appears to be valid based on what we've fetched so far.
	 * @param max The highest CompatibilityMode that appears to be valid based on what we've fetched so far.
	 * @param customSplitfileKey The fixed byte[] encryption key used on insert. On anything recent, we generate a single key, randomly for an SSK,
	 * or based on the content for a CHK, and use it for everything. This saves metadata space and improves security for SSKs.
	 * @param compressed Whether the content is compressed. If false, the dontCompress option was used.
	 * @param bottomLayer Whether this report originates at the bottom layer of the splitfile pyramid. I.e. the actual file, not the file containing
	 * the metadata to fetch the file (this can recurse for several levels!)
	 * @param definitiveAnyway Whether this report is definitive even though it's not from the bottom layer. This is true of recent splitfiles, 
	 * where we store all the data in the top key.
	 * @param container The database handle. Must not be used by other threads.
	 * @param context Utility object containing helpers, mostly not persistent, such as the Ticker, temporary storage factories etc.
	 */
	public void onSplitfileCompatibilityMode(CompatibilityMode min, CompatibilityMode max, byte[] customSplitfileKey, boolean compressed, boolean bottomLayer, boolean definitiveAnyway, ObjectContainer container, ClientContext context);

	/**
	 * Called when we know the HashResult of the final file. This will be checked when we actually 
	 * fetch it, so is guaranteed to be correct. For recent metadata this is known at the top 
	 * layer/block.
	 * @param hashes A set of hashes for the final file content.
	 * @param container The database handle. Must not be used by other threads.
	 * @param context Utility object containing helpers, mostly not persistent, such as the Ticker, temporary storage factories etc.
	 */
	public void onHashes(HashResult[] hashes, ObjectContainer container, ClientContext context);
}
