/*
  ArchiveStoreItem.java / Freenet
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

import freenet.support.DoublyLinkedListImpl;
import freenet.support.io.Bucket;

/**
 * Base class for items stored in the archive cache.
 */
abstract class ArchiveStoreItem extends DoublyLinkedListImpl.Item {
	final ArchiveKey key;
	final ArchiveStoreContext context;
	
	/** Basic constructor. */
	ArchiveStoreItem(ArchiveKey key, ArchiveStoreContext context) {
		this.key = key;
		this.context = context;
		context.addItem(this);
	}
	
	/** Expected to delete any stored data on disk, and decrement cachedData.
	 * Implemented to remove self from context.
	 */
	protected void finalize() {
		context.removeItem(this);
	}

	/**
	 * Return cached data as a Bucket, or throw an ArchiveFailureException.
	 */
	abstract Bucket getDataOrThrow() throws ArchiveFailureException;
}
