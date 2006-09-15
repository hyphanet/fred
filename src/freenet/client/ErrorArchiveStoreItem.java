/*
  ErrorArchiveStoreItem.java / Freenet
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

import freenet.keys.FreenetURI;
import freenet.support.io.Bucket;

class ErrorArchiveStoreItem extends ArchiveStoreItem {

	/** Error message. Usually something about the file being too big. */
	String error;
	
	/**
	 * Create a placeholder item for a file which could not be extracted from the archive.
	 * @param ctx The context object which tracks all the items with this key.
	 * @param key2 The key from which the archive was fetched.
	 * @param name The name of the file which failed to extract.
	 * @param error The error message to be included in the thrown exception when
	 * somebody tries to get the data.
	 */
	public ErrorArchiveStoreItem(ArchiveStoreContext ctx, FreenetURI key2, String name, String error) {
		super(new ArchiveKey(key2, name), ctx);
		this.error = error;
	}

	/**
	 * Throws an exception with the given error message, because this file could not be
	 * extracted from the archive.
	 */
	Bucket getDataOrThrow() throws ArchiveFailureException {
		throw new ArchiveFailureException(error);
	}
	
}
