package freenet.client.filter;

import static freenet.client.filter.ResourceFileUtil.resourceToBucket;
import static org.junit.Assert.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.junit.Test;

import freenet.support.api.Bucket;
import freenet.support.io.ArrayBucket;
import freenet.support.io.BucketTools;


public class BMPFilterTest {
    /**
     * File of size less than 54 bytes
     */
    @Test
    public void testTooShortImage() throws IOException {
        Bucket input = resourceToBucket("./bmp/small.bmp");
        filterImage(input, DataFilterException.class);
    }

    /**
     * Illegal start word (AB instead of BM)
     */
    @Test
    public void testIllegalStartWord() throws IOException {
        Bucket input = resourceToBucket("./bmp/one.bmp");
        filterImage(input, DataFilterException.class);
    }

    /**
     * Invalid offset i.e. starting address
     */
    @Test
    public void testInvalidOffset() throws IOException {
        Bucket input = resourceToBucket("./bmp/two.bmp");
        filterImage(input, DataFilterException.class);
    }

    /**
     * Invalid size of bitmap info header
     */
    @Test
    public void testInvalidBitmapInfoHeaderSize() throws IOException {
        Bucket input = resourceToBucket("./bmp/three.bmp");
        filterImage(input, DataFilterException.class);
    }

    /**
     * Negative image width
     */
    @Test
    public void testNegativeImageWidth() throws IOException {
        Bucket input = resourceToBucket("./bmp/four.bmp");
        filterImage(input, DataFilterException.class);
    }

    /**
     * Invalid number of planes
     */
    @Test
    public void testInvalidNumberOfPlanes() throws IOException {
        Bucket input = resourceToBucket("./bmp/five.bmp");
        filterImage(input, DataFilterException.class);
    }

    /**
     * Invalid bit depth
     */
    @Test
    public void testInvalidBitDepth() throws IOException {
        Bucket input = resourceToBucket("./bmp/six.bmp");
        filterImage(input, DataFilterException.class);
    }

    /**
     * Invalid compression type
     */
    @Test
    public void testInvalidCompressionType() throws IOException {
        Bucket input = resourceToBucket("./bmp/seven.bmp");
        filterImage(input, DataFilterException.class);
    }

    /**
     * Invalid image data size (i.e. not satisfying fileSize = headerSize + imagedatasize)
     */
    @Test
    public void testInvalidImageDataSize() throws IOException {
        Bucket input = resourceToBucket("./bmp/eight.bmp");
        filterImage(input, DataFilterException.class);
    }

    /**
     * Invalid image resolution
     */
    @Test
    public void testInvalidImageResolution() throws IOException {
        Bucket input = resourceToBucket("./bmp/nine.bmp");
        filterImage(input, DataFilterException.class);
    }

    /**
     * File is shorter than expected
     */
    @Test
    public void testNotEnoughImageData() throws IOException {
        Bucket input = resourceToBucket("./bmp/ten.bmp");
        filterImage(input, DataFilterException.class);
    }

    /**
     * Tests valid image
     */
    @Test
    public void testValidImage() throws IOException {
        Bucket input = resourceToBucket("./bmp/ok.bmp");
        Bucket output = filterImage(input, null);

        //Filter should return the original
        assertEquals("Input and output should be the same length", input.size(), output.size());
        assertArrayEquals("Input and output are not identical", BucketTools.toByteArray(input), BucketTools.toByteArray(output));
    }

    /**
     * Checks that the image size calculation works for images with padding
     */
    @Test
    public void testImageSizeCalculationWithPadding() throws IOException {
        Bucket input = resourceToBucket("./bmp/sizeCalculationWithPadding.bmp");
        Bucket output = filterImage(input, null);

        //Filter should return the original
        assertEquals("Input and output should be the same length", input.size(), output.size());
        assertArrayEquals("Input and output are not identical", BucketTools.toByteArray(input), BucketTools.toByteArray(output));
    }

    /**
     * Checks that the image size calculation works for images without padding
     */
    @Test
    public void testImageSizeCalculationWithoutPadding() throws IOException {
        Bucket input = resourceToBucket("./bmp/sizeCalculationWithoutPadding.bmp");
        Bucket output = filterImage(input, null);

        //Filter should return the original
        assertEquals("Input and output should be the same length", input.size(), output.size());
        assertArrayEquals("Input and output are not identical", BucketTools.toByteArray(input), BucketTools.toByteArray(output));
    }

    private Bucket filterImage(Bucket input, Class<? extends Exception> expected) throws IOException {
        BMPFilter objBMPFilter = new BMPFilter();
        Bucket output = new ArrayBucket();
        try (
            InputStream inStream = input.getInputStream();
            OutputStream outStream = output.getOutputStream()
        ) {
            if (expected != null) {
                assertThrows(expected, () -> readFilter(objBMPFilter, inStream, outStream));
            } else {
                readFilter(objBMPFilter, inStream, outStream);
            }
        }
        return output;
    }

    private static void readFilter(BMPFilter objBMPFilter, InputStream inStream, OutputStream outStream) throws IOException {
        objBMPFilter.readFilter(inStream, outStream, "", null, null, null);
    }
}
