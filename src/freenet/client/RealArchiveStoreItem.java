/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import com.db4o.ObjectContainer;

import freenet.keys.FreenetURI;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.io.MultiReaderBucket;

class RealArchiveStoreItem extends ArchiveStoreItem {

	private final MultiReaderBucket mb;
	private final Bucket bucket;
	private final long spaceUsed;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	/**
	 * Create an ArchiveStoreElement from a TempStoreElement.
	 * @param key2 The key of the archive the file came from.
	 * @param realName The name of the file in that archive.
	 * @param temp The TempStoreElement currently storing the data.
	 * @param manager The parent ArchiveManager within which this item is stored.
	 */
	RealArchiveStoreItem(ArchiveStoreContext ctx, FreenetURI key2, String realName, Bucket bucket) {
		super(new ArchiveKey(key2, realName), ctx);
		if(bucket == null) throw new NullPointerException();
		mb = new MultiReaderBucket(bucket);
		this.bucket = mb.getReaderBucket();
		if(this.bucket == null) throw new NullPointerException();
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
	@Override
	long spaceUsed() {
		return spaceUsed;
	}
	
	@Override
	void innerClose() {
		if(logMINOR)
			Logger.minor(this, "innerClose(): "+this+" : "+bucket);
		if(bucket == null) {
			// This still happens. It is clearly impossible as we check in the constructor and throw if it is null.
			// Nonetheless there is little we can do here ...
			Logger.error(this, "IMPOSSIBLE: BUCKET IS NULL!", new Exception("error"));
			return;
		}
		bucket.free();
	}

	@Override
	Bucket getDataOrThrow() throws ArchiveFailureException {
		return dataAsBucket();
	}

	@Override
	Bucket getReaderBucket() throws ArchiveFailureException {
		return mb.getReaderBucket();
	}
	
	public boolean objectCanNew(ObjectContainer container) {
		Logger.error(this, "Trying to store an ArchiveStoreItem!", new Exception("error"));
		return false;
	}
	
	public boolean objectCanUpdate(ObjectContainer container) {
		Logger.error(this, "Trying to store an ArchiveStoreItem!", new Exception("error"));
		return false;
	}
	
	public boolean objectCanActivate(ObjectContainer container) {
		Logger.error(this, "Trying to store an ArchiveStoreItem!", new Exception("error"));
		return false;
	}
	
	public boolean objectCanDeactivate(ObjectContainer container) {
		Logger.error(this, "Trying to store an ArchiveStoreItem!", new Exception("error"));
		return false;
	}
	
}
