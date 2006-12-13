/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import java.io.IOException;

import freenet.support.api.Bucket;


public class PersistentEncryptedTempBucketFactory implements BucketFactory {

	PersistentTempBucketFactory bf;
	
	public PersistentEncryptedTempBucketFactory(PersistentTempBucketFactory bf) {
		this.bf = bf;
	}

	public Bucket makeBucket(long size) throws IOException {
		return bf.makeEncryptedBucket();
	}

	public void freeBucket(Bucket b) throws IOException {
		bf.freeBucket(b);
	}
}
