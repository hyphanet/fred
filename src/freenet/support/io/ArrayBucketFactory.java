/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import java.io.IOException;

import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;

/**
 *
 * @author unknown
 */
public class ArrayBucketFactory implements BucketFactory {

	public Bucket makeBucket(long size) throws IOException {
		return new ArrayBucket();
	}

	/**
	 *
	 * @param b
	 * @throws IOException
	 */
	public void freeBucket(Bucket b) throws IOException {
		b.free();
	}

}
