package freenet.client;

import freenet.client.async.ClientContext;
import freenet.support.api.Bucket;

/** Called when we have extracted an archive, and a specified file either is
 * or isn't in it. */
public interface ArchiveExtractCallback {

	/** Got the data.
	 * Note that the bucket will be persistent if the caller asked for an off-thread extraction. */
	public void gotBucket(Bucket data, ClientContext context);
	
	/** Not in the archive */
	public void notInArchive(ClientContext context);
	
	/** Failed: restart */
	public void onFailed(ArchiveRestartException e, ClientContext context);
	
	/** Failed for some other reason */
	public void onFailed(ArchiveFailureException e, ClientContext context);

}
