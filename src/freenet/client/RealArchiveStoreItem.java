/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import freenet.keys.FreenetURI;
import freenet.support.api.Bucket;
import freenet.support.io.MultiReaderBucket;

class RealArchiveStoreItem extends ArchiveStoreItem {

	private final MultiReaderBucket mb;
	private final Bucket bucket;
	private final long spaceUsed;
	
	/**
	 * Create an ArchiveStoreElement from a TempStoreElement.
	 * @param key2 The key of the archive the file came from.
	 * @param realName The name of the file in that archive.
	 * @param temp The TempStoreElement currently storing the data.
	 * @param manager The parent ArchiveManager within which this item is stored.
	 */
	RealArchiveStoreItem(ArchiveStoreContext ctx, FreenetURI key2, String realName, Bucket bucket) {
		super(new ArchiveKey(key2, realName), ctx);
		mb = new MultiReaderBucket(bucket);
		this.bucket = mb.getReaderBucket();
		bucket.setReadOnly();
		spaceUsed = bucket.size();
	}

	/**
	 * Return the data, as a Bucket, in plaintext.
	 */
	Bucket dataAsBucket() {
		return bucket;
	}

	/**
	 * Return the length of the data.
	 */
	long dataSize() {
		return bucket.size();
	}

	/**
	 * Return the estimated space used by the data.
	 */
	long spaceUsed() {
		return spaceUsed;
	}
	
	@Override
	void innerClose() {
		bucket.free();
	}

	Bucket getDataOrThrow() throws ArchiveFailureException {
		return dataAsBucket();
	}

	Bucket getReaderBucket() throws ArchiveFailureException {
		return mb.getReaderBucket();
	}
}
