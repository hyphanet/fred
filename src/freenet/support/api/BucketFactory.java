/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.api;

import java.io.IOException;


public interface BucketFactory {
	/**
	 * Create a bucket.
	 * @param size The maximum size of the data, or -1 or Long.MAX_VALUE if we don't know.
	 * Some buckets will throw IOException if you go over this length.
	 * @return
	 * @throws IOException
	 */
    public RandomAccessBucket makeBucket(long size) throws IOException;
    
}

