/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import java.io.File;
import java.io.IOException;
import java.util.Random;

import freenet.support.api.Bucket;

public class TempFileBucketTest extends BucketTestBase {
	private Random weakPRNG = new Random(12345);

	@Override
	protected Bucket makeBucket(long size) throws IOException {
		FilenameGenerator filenameGenerator = new FilenameGenerator(weakPRNG, false, null, "junit");
		BaseFileBucket bfb = new TempFileBucket(filenameGenerator.makeRandomFilename(), filenameGenerator);

		assertTrue("deleteOnFree", bfb.deleteOnFree());

		return bfb;
	}

	@Override
	protected void freeBucket(Bucket bucket) throws IOException {
		File file = ((BaseFileBucket) bucket).getFile();
		if (bucket.size() != 0)
			assertTrue("TempFile not exist", file.exists());
		bucket.free();
		assertFalse("TempFile not deleted", file.exists());
	}
}
