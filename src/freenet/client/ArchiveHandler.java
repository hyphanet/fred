/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import com.db4o.ObjectContainer;

import freenet.client.ArchiveManager.ARCHIVE_TYPE;
import freenet.client.async.ClientContext;
import freenet.keys.FreenetURI;
import freenet.support.api.Bucket;

/**
 * @author toad
 * The public face (to Fetcher, for example) of ArchiveStoreContext.
 * Mostly has methods for fetching stuff, but SingleFileFetcher needs to be able
 * to download and then ask the ArchiveManager to extract it, so we include that 
 * functionality (extractToCache) too. Because ArchiveManager is not persistent,
 * we have to pass it in to each method.
 */
public interface ArchiveHandler {

	/**
	 * Get the metadata for this ZIP manifest, as a Bucket.
	 * THE RETURNED BUCKET WILL ALWAYS BE NON-PERSISTENT.
	 * @return The metadata as a Bucket, or null.
	 * @param manager The ArchiveManager.
	 * @throws FetchException If the container could not be fetched.
	 * @throws MetadataParseException If there was an error parsing intermediary metadata.
	 */
	public abstract Bucket getMetadata(ArchiveContext archiveContext,
			ArchiveManager manager, ObjectContainer container)
			throws ArchiveFailureException, ArchiveRestartException,
			MetadataParseException, FetchException;

	/**
	 * Get a file from this ZIP manifest, as a Bucket.
	 * If possible, read it from cache. If not, return null.
	 * THE RETURNED BUCKET WILL ALWAYS BE NON-PERSISTENT.
	 * @param inSplitZipManifest If true, indicates that the key points to a splitfile zip manifest,
	 * which means that we need to pass a flag to the fetcher to tell it to pretend it was a straight
	 * splitfile.
	 * @param manager The ArchiveManager.
	 * @throws FetchException 
	 * @throws MetadataParseException 
	 */
	public abstract Bucket get(String internalName,
			ArchiveContext archiveContext, ArchiveManager manager, ObjectContainer container)
			throws ArchiveFailureException, ArchiveRestartException,
			MetadataParseException, FetchException;

	/**
	 * Get the archive type.
	 */
	public abstract ARCHIVE_TYPE getArchiveType();

	/**
	 * Get the key.
	 */
	public abstract FreenetURI getKey();
	
	/**
	 * Unpack a fetched archive to cache, and call the callback if there is one.
	 * @param bucket The downloaded data for the archive.
	 * @param actx The ArchiveContext.
	 * @param element The single element that the caller is especially interested in.
	 * @param callback Callback to be notified whether the content is available, and if so, fed the data.
	 * @param manager The ArchiveManager.
	 * @throws ArchiveFailureException
	 * @throws ArchiveRestartException
	 */
	public abstract void extractToCache(Bucket bucket, ArchiveContext actx, String element, ArchiveExtractCallback callback, ArchiveManager manager, 
			ObjectContainer container, ClientContext context) throws ArchiveFailureException, ArchiveRestartException;

	/**
	 * Unpack a fetched archive on a separate thread for a persistent caller.
	 * This involves:
	 * - Add a tag to the database so that it will be restarted on a crash.
	 * - Run the actual unpack on a separate thread.
	 * - Copy the data to a persistent bucket.
	 * - Schedule a database job.
	 * - Call the callback.
	 * @param bucket
	 * @param actx
	 * @param element
	 * @param callback
	 * @param container
	 * @param context
	 */
	public abstract void extractPersistentOffThread(Bucket bucket, boolean freeBucket, ArchiveContext actx, String element, ArchiveExtractCallback callback, ObjectContainer container, ClientContext context);
	
	public abstract void activateForExecution(ObjectContainer container);

	public abstract ArchiveHandler cloneHandler();

	public abstract void removeFrom(ObjectContainer container);
	
}