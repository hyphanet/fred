/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import freenet.support.DoublyLinkedListImpl;
import freenet.support.api.Bucket;

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
