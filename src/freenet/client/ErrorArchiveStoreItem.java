package freenet.client;

import freenet.keys.FreenetURI;
import freenet.support.Bucket;

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

	public void finalize() {
		super.finalize();
	}

	/**
	 * Throws an exception with the given error message, because this file could not be
	 * extracted from the archive.
	 */
	Bucket getDataOrThrow() throws ArchiveFailureException {
		throw new ArchiveFailureException(error);
	}
	
}
