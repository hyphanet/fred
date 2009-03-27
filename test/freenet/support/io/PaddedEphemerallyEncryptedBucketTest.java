/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import java.io.IOException;
import java.util.Random;

import freenet.crypt.DummyRandomSource;
import freenet.crypt.RandomSource;
import freenet.support.api.Bucket;

public class PaddedEphemerallyEncryptedBucketTest extends BucketTestBase {
	private RandomSource strongPRNG = new DummyRandomSource(12345);
	private Random weakPRNG = new DummyRandomSource(54321);
	
	@Override
	protected Bucket makeBucket(long size) throws IOException {
		FilenameGenerator filenameGenerator = new FilenameGenerator(weakPRNG, false, null, "junit");
		TempFileBucket fileBucket = new TempFileBucket(filenameGenerator.makeRandomFilename(), filenameGenerator);
		return new PaddedEphemerallyEncryptedBucket(fileBucket, 1024, strongPRNG, weakPRNG);
	}

	@Override
	protected void freeBucket(Bucket bucket) throws IOException {
		bucket.free();
	}
}
