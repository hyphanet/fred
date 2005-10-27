package freenet.client;

/**
 * Base class for items stored in the archive cache.
 */
abstract class ArchiveStoreItem {
	final ArchiveKey key;
	final ArchiveStoreContext context;
	
	ArchiveStoreItem(ArchiveKey key, ArchiveStoreContext context) {
		this.key = key;
		this.context = context;
	}
	
	/** Expected to delete any stored data on disk, and decrement cachedData. */
	public abstract void finalize();
}
