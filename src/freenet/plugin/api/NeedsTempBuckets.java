package freenet.plugin.api;

import freenet.support.api.BucketFactory;

/**
 * A plugin must implement this interface if it will need to create temporary buckets.
 */
public interface NeedsTempBuckets {

	/** How much space does the plugin require, at most? */
	public long spaceRequired();
	
	public void register(BucketFactory provider);
	
}
