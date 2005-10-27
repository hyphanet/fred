package freenet.client;

import java.io.File;

import freenet.keys.FreenetURI;
import freenet.support.Bucket;
import freenet.support.PaddedEphemerallyEncryptedBucket;
import freenet.support.io.FileBucket;
import freenet.support.io.FileUtil;

class RealArchiveStoreItem extends ArchiveStoreItem {

	private final ArchiveManager manager;
	boolean finalized;
	File myFilename;
	PaddedEphemerallyEncryptedBucket bucket;
	FileBucket underBucket;
	
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
		underBucket.setReadOnly();
		this.manager.cachedData += spaceUsed();
	}

	Bucket dataAsBucket() {
		return bucket;
	}

	long dataSize() {
		return bucket.size();
	}
	
	long spaceUsed() {
		return FileUtil.estimateUsage(myFilename, underBucket.size());
	}
	
	public synchronized void finalize() {
		if(finalized) return;
		long sz = spaceUsed();
		underBucket.finalize();
		finalized = true;
		this.manager.cachedData -= sz;
	}
}