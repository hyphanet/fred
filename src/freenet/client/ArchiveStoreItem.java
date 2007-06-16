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

	/** Delete any stored data on disk etc. Override in subtypes for specific cleanup. */
	void innerClose() { } // override in subtypes for cleanup
	
	/** 
	 * Shortcut to start the removal/cleanup process.
	 */
	final void close() {
		context.removeItem(this);
	}

	/**
	 * Return cached data as a Bucket, or throw an ArchiveFailureException.
	 */
	abstract Bucket getDataOrThrow() throws ArchiveFailureException;

	/**
	 * Return the amount of cache space used by the item.
	 */
	abstract long spaceUsed();
	
	/**
	 * Get the data as a Bucket, and guarantee that it won't be freed until the
	 * returned object is either finalized or freed.
	 */
	abstract Bucket getReaderBucket() throws ArchiveFailureException;
}
