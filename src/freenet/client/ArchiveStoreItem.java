package freenet.client;

import freenet.support.DoublyLinkedListImpl;

/**
 * Base class for items stored in the archive cache.
 */
abstract class ArchiveStoreItem extends DoublyLinkedListImpl.Item {
	final ArchiveKey key;
	final ArchiveStoreContext context;
	
	ArchiveStoreItem(ArchiveKey key, ArchiveStoreContext context) {
		this.key = key;
		this.context = context;
		context.addItem(this);
	}
	
	/** Expected to delete any stored data on disk, and decrement cachedData.
	 * Implemented to remove self from context.
	 */
	public void finalize() {
		context.removeItem(this);
	}
}
