package freenet.client.filter;

import junit.framework.TestCase;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import freenet.support.HexUtil;
import freenet.support.api.Bucket;
import freenet.support.io.ArrayBucket;
import freenet.support.io.BucketTools;
import freenet.support.io.NullOutputStream;

public class GIFFilterTest extends TestCase {
    private static final String RESOURCE_PATH = "gif/";

    /** Known good files, should pass filter unaltered. */
    private static final String[] GOOD = {
        /*
         * Various samples from Firefox testcases.
         */
         
        // GIF image data, version 87a, 40 x 40
        "animated-gif-finalframe.gif",
        // GIF image data, version 87a, 92 x 129
        "clean.gif",
        // GIF image data, version 87a, 85 x 140
        "bethlehem.gif",
        // GIF image data, version 87a, 92 x 140
        "bill.gif",
        // GIF image data, version 87a, 90 x 140
        "charing.gif",
        // GIF image data, version 87a, 92 x 140
        "welville.gif",
        
        // GIF image data, version 89a, 100 x 100
        "animated1.gif",
        // GIF image data, version 89a, 40 x 40
        "animated-gif2.gif",
        // GIF image data, version 89a, 40 x 40
        "animated-gif.gif",
        // GIF image data, version 89a, 200 x 200
        "bug1132427.gif",
        // GIF image data, version 89a, 100 x 75
        "clear2.gif",
        // GIF image data, version 89a, 100 x 75
        "clear2-results.gif",
        // GIF image data, version 89a, 100 x 100
        "clear.gif",
        // GIF image data, version 89a, 16 x 16
        "first-frame-padding.gif",
        // GIF image data, version 89a, 100 x 100
        "keep.gif",
        // GIF image data, version 89a, 40 x 40
        "purple.gif",
        // GIF image data, version 89a, 1 x 1
        "red.gif",
        // GIF image data, version 89a, 100 x 100
        "restore-previous.gif",
        // GIF image data, version 89a, 16 x 16
        "transparent.gif"
    };

    /** Pairs of unfiltered file and their expected output file. */
    private static final String[][] FILTER_PAIRS = {
        // Check if we strip garbage after the end of the file.
        new String[] {
            // GIF image data, version 89a, 40 x 40
            "animated-gif_trailing-garbage.gif",
            "animated-gif_trailing-garbage.filtered.gif",
        },
        // Check if we strip short application extensions.
        new String[] {
            // GIF image data, version 89a, 353 x 25
            "short_header.gif",
            "short_header.filtered.gif",
        },
        // Stripping of long application extensions.
        new String[] {
            // GIF image data, version 89a, 60 x 60
            "share-the-safety-like.gif",
            "share-the-safety-like.filtered.gif"
        },
        // Stripping of graphic control appearing in GIF87a.
        new String[] {
            // GIF image data, version 87a, 101 x 140
            "road.gif",
            "road.filtered.gif"
        }
    };

    private static final String[] REJECT = {
        // MPEG ADTS, layer III,  v2.5,  48 kbps, 11.025 kHz, Stereo
        "11khz-48kbps-cbr-stereo.mp3",
        // PNG image data, 32 x 32, 1-bit grayscale, non-interlaced
        "basn0g01.png",
        // Truncated GIF file (share-the-safety-like.gif with removed terminator)
        "share-the-safety-like.truncated.gif"
    };

    public void testKnownGood() {
        for (String good : GOOD) {
            assertEqualAfterFilter(good, good);
        }
    }

    public void testFilterPairs() {
        for (String[] pair : FILTER_PAIRS) {
            assertEqualAfterFilter(pair[0], pair[1]);
        }
    }

    public void testReject() {
        for (String reject : REJECT) {
            final InputStream inStream;
            final NullOutputStream outStream;
            try {
                inStream = resourceToBucket(reject).getInputStream();
                outStream = new NullOutputStream();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            ContentDataFilter filter = new GIFFilter();
            try {
                filter.readFilter(inStream, outStream, "", null, null);
                fail("Filter did not fail on reject sample " + reject);
            } catch (DataFilterException e) {
                // Expected.
            } catch (Exception e) {
                throw new AssertionError("Unexpected exception in the content filter.", e);
            }
            try {
                inStream.close();
                outStream.close();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }
    }

    /**
     * Asserts that the test file in the first argument, after passing through the content filter,
     * is equal to the reference file in the second argument.
     *
     * @param fileUnfiltered  the test file
     * @param fileExpected    the reference file
     */
    private static void assertEqualAfterFilter(String fileUnfiltered, String fileExpected) {
        Bucket input = resourceToBucket(fileUnfiltered);
        Bucket expected = resourceToBucket(fileExpected);
        Bucket filtered = filterGIF(input);
        assertTrue("Filtered and expected output are not identical. " +
            "Input = " + fileUnfiltered + ", expected = " + fileExpected,
            equalBuckets(filtered, expected)
        );
    }

    /**
     * Checks for equality of Bucket contents.
     */
    private static boolean equalBuckets(Bucket a, Bucket b) {
        try {
            return Arrays.equals(BucketTools.toByteArray(a), BucketTools.toByteArray(b));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Runs a Bucket through the content filter.
     *
     * @throws AssertionError on failure
     */
    private static Bucket filterGIF(Bucket input) {
        ContentDataFilter filter = new GIFFilter();
        Bucket output = new ArrayBucket();

        InputStream inStream;
        OutputStream outStream;
        try {
            inStream = input.getInputStream();
            outStream = output.getOutputStream();
        } catch (IOException e) {
            throw new AssertionError(e);
        }

        try {
            filter.readFilter(inStream, outStream, "", null, null);
        } catch (Exception e) {
            throw new AssertionError("Unexpected exception in the content filter.", e);
        }

        try {
            inStream.close();
            outStream.close();
        } catch (IOException e) {
            throw new AssertionError(e);
        }

        return output;
    }

    /**
     * Loads a resource relative to the resource path into a Bucket.
     *
     * @throws AssertionError on failure
     */
    private static Bucket resourceToBucket(String filename) {
        InputStream is = GIFFilterTest.class.getResourceAsStream(RESOURCE_PATH + filename);
        if (is == null) {
            throw new AssertionError("Test resource could not be opened: " + filename);
        }
        Bucket ab = new ArrayBucket();
        try {
            BucketTools.copyFrom(ab, is, Long.MAX_VALUE);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return ab;
    }
}
