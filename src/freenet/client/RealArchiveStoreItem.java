/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import java.io.File;

import freenet.keys.FreenetURI;
import freenet.support.api.Bucket;
import freenet.support.io.FileBucket;
import freenet.support.io.FileUtil;
import freenet.support.io.PaddedEphemerallyEncryptedBucket;

class RealArchiveStoreItem extends ArchiveStoreItem {

	private final ArchiveManager manager;
	private boolean finalized;
	private final File myFilename;
	private final PaddedEphemerallyEncryptedBucket bucket;
	private final FileBucket underBucket;
	
	/**
	 * Create an ArchiveStoreElement from a TempStoreElement.
	 * @param key2 The key of the archive the file came from.
	 * @param realName The name of the file in that archive.
	 * @param temp The TempStoreElement currently storing the data.
	 * @param manager The parent ArchiveManager within which this item is stored.
	 */
	RealArchiveStoreItem(ArchiveManager manager, ArchiveStoreContext ctx, FreenetURI key2, String realName, TempStoreElement temp) {
		super(new ArchiveKey(key2, realName), ctx);
		this.manager = manager;
		this.finalized = false;
		this.bucket = temp.bucket;
		this.underBucket = temp.underBucket;
		underBucket.dontDeleteOnFinalize();
		underBucket.setReadOnly();
		this.myFilename = underBucket.getFile();
		this.manager.cachedData += spaceUsed();
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
	synchronized long spaceUsed() {
		return FileUtil.estimateUsage(myFilename, underBucket.size());
	}
	
	synchronized void close() {
		super.close();
		if(finalized) return;
		long sz = spaceUsed();
		underBucket.finalize();
		finalized = true;
		this.manager.cachedData -= sz;
	}

	Bucket getDataOrThrow() throws ArchiveFailureException {
		return dataAsBucket();
	}
}