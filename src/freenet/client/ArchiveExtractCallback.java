package freenet.client;

import freenet.support.api.Bucket;

/** Called when we have extracted an archive, and a specified file either is
 * or isn't in it. */
public interface ArchiveExtractCallback {

	/** Got the data */
	public void gotBucket(Bucket data);
	
	/** Not in the archive */
	public void notInArchive();
	
}
