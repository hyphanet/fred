/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
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
