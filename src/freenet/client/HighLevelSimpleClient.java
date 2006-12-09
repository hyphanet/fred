/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import java.util.HashMap;

import freenet.client.events.ClientEventListener;
import freenet.keys.FreenetURI;

public interface HighLevelSimpleClient {

	/**
	 * Set the maximum length of the fetched data.
	 */
	public void setMaxLength(long maxLength);
	
	/**
	 * Set the maximum length of any intermediate data, e.g. ZIP manifests.
	 */
	public void setMaxIntermediateLength(long maxIntermediateLength);

	/**
	 * Blocking fetch of a URI
	 * @throws FetchException If there is an error fetching the data
	 */
	public FetchResult fetch(FreenetURI uri) throws FetchException;

	/**
	 * Blocking fetch of a URI with a configurable max-size.
	 */
	public FetchResult fetch(FreenetURI uri, long maxSize) throws FetchException;
	
	/**
	 * Blocking fetch of a URI with a configurable max-size and context object.
	 */
	public FetchResult fetch(FreenetURI uri, long maxSize, Object context) throws FetchException;
	
	/**
	 * Blocking insert.
	 * @param filenameHint If set, insert a single-file manifest containing only this file, under the given filename.
	 * @throws InserterException If there is an error inserting the data
	 */
	public FreenetURI insert(InsertBlock insert, boolean getCHKOnly, String filenameHint) throws InserterException;

	/**
	 * Blocking insert of a redirect.
	 */
	public FreenetURI insertRedirect(FreenetURI insertURI, FreenetURI target) throws InserterException;
	
	/**
	 * Blocking insert of multiple files as a manifest (or zip manifest, etc).
	 */
	public FreenetURI insertManifest(FreenetURI insertURI, HashMap bucketsByName, String defaultName) throws InserterException;
	
	public FetcherContext getFetcherContext();

	/**
	 * Get an InserterContext.
	 * @param forceNonPersistent If true, force the request to use the non-persistent
	 * bucket pool.
	 */
	public InserterContext getInserterContext(boolean forceNonPersistent);
	
	/**
	 * Add a ClientEventListener.
	 */
	public void addGlobalHook(ClientEventListener listener);

}
