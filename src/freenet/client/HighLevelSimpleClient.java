/*
  HighLevelSimpleClient.java / Freenet
  Copyright (C) 2005-2006 The Free Network project

  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

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
	 * Blocking insert.
	 * @throws InserterException If there is an error inserting the data
	 */
	public FreenetURI insert(InsertBlock insert, boolean getCHKOnly) throws InserterException;

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
