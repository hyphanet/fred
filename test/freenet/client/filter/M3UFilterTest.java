package freenet.client.filter;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;

import junit.framework.TestCase;
import freenet.support.api.Bucket;
import freenet.support.io.ArrayBucket;
import freenet.support.io.BucketTools;
import freenet.support.io.NullBucket;

public class M3UFilterTest extends TestCase {
    protected static Object[][] testPlaylists = {
        { "./m3u/safe.m3u", "./m3u/safe_madesafe.m3u" },
        { "./m3u/unsafe.m3u", "./m3u/unsafe_madesafe.m3u" },
    };

    public void testSuiteTest() throws IOException {
		M3UFilter filter = new M3UFilter();

		for (Object[] test : testPlaylists) {
			String original = (String) test[0];
			String correct = (String) test[1];
			Bucket ibo;
			Bucket ibprocessed = new ArrayBucket();
			Bucket ibc;
			try {
				ibo = resourceToBucket(original);
			} catch (IOException e) {
				System.out.println(original + " not found, test skipped");
				continue;
			}
			try {
				ibc = resourceToBucket(correct);
			} catch (IOException e) {
				System.out.println(correct + " not found, test skipped");
				continue;
			}

			try {
				filter.readFilter(ibo.getInputStream(), ibprocessed.getOutputStream(), "UTF-8", null, null);
                String result = bucketToString((ArrayBucket)ibprocessed);

				assertTrue(original + " should be filtered as " + correct + " but was filtered as " + result + " instead of " + bucketToString((ArrayBucket)ibc), result.equals(bucketToString((ArrayBucket)ibc)));
			} catch (DataFilterException dfe) {
				assertTrue("Filtering " + original + " failed", false);
			}
		}
	}

	protected ArrayBucket resourceToBucket(String filename) throws IOException {
		InputStream is = getClass().getResourceAsStream(filename);
		if (is == null) throw new java.io.FileNotFoundException();
		ArrayBucket ab = new ArrayBucket();
		BucketTools.copyFrom(ab, is, Long.MAX_VALUE);
		return ab;
	}

    protected String bucketToString(ArrayBucket bucket) throws IOException {
        return new String(bucket.toByteArray());
    }

}
