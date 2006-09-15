/*
  ArchiveHandler.java / Freenet
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

import freenet.support.io.Bucket;

/**
 * The public face (to Fetcher, for example) of ArchiveStoreContext.
 * Just has methods for fetching stuff.
 */
public interface ArchiveHandler {

	/**
	 * Get the metadata for this ZIP manifest, as a Bucket.
	 * @throws FetchException If the container could not be fetched.
	 * @throws MetadataParseException If there was an error parsing intermediary metadata.
	 */
	public abstract Bucket getMetadata(ArchiveContext archiveContext,
			ClientMetadata dm, int recursionLevel, 
			boolean dontEnterImplicitArchives)
			throws ArchiveFailureException, ArchiveRestartException,
			MetadataParseException, FetchException;

	/**
	 * Get a file from this ZIP manifest, as a Bucket.
	 * If possible, read it from cache. If necessary, refetch the 
	 * container and extract it. If that fails, throw.
	 * @param inSplitZipManifest If true, indicates that the key points to a splitfile zip manifest,
	 * which means that we need to pass a flag to the fetcher to tell it to pretend it was a straight
	 * splitfile.
	 * @throws FetchException 
	 * @throws MetadataParseException 
	 */
	public abstract Bucket get(String internalName,
			ArchiveContext archiveContext, 
			ClientMetadata dm, int recursionLevel, 
			boolean dontEnterImplicitArchives)
			throws ArchiveFailureException, ArchiveRestartException,
			MetadataParseException, FetchException;

	/**
	 * Get the archive type.
	 */
	public abstract short getArchiveType();

}