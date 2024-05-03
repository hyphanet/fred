package freenet.client.filter;

import static org.junit.Assert.*;
import static freenet.client.filter.ResourceFileUtil.resourceToBucket;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;

import org.junit.Test;
import freenet.support.api.Bucket;
import freenet.support.io.ArrayBucket;
import freenet.support.io.BucketTools;

/**
 * Unit test for (parts of) {@link WebPFilter}.
 */
public class WebPFilterTest {

    /**
     * Tests file without media chunk
     */
    @Test
    public void testNoChunkFile() throws IOException {
        Bucket input = resourceToBucket("./webp/nochunks.not_webp");
        filterImage(input, DataFilterException.class);
    }

    /**
     * Tests file with too short VP8 chunk
     */
    @Test
    public void testTruncatedFile() throws IOException {
        Bucket input = resourceToBucket("./webp/test_truncated.not_webp");
        filterImage(input, DataFilterException.class);
    }

    /**
     * Tests file with chunk size of 0x7fffffff
     */
    @Test
    public void testTooBig() throws IOException {
        Bucket input = resourceToBucket("./webp/too_big.not_webp");
        filterImage(input, DataFilterException.class);
    }
    
    /**
     * Tests valid image
     */
    @Test
    public void testValidImage() throws IOException {
        Bucket input = resourceToBucket("./webp/test.webp");
        Bucket output = filterImage(input, null);

        //Filter should return the original
        assertEquals("Input and output should be the same length", input.size(), output.size());
        assertArrayEquals("Input and output are not identical", BucketTools.toByteArray(input), BucketTools.toByteArray(output));
    }

    private Bucket filterImage(Bucket input, Class<? extends Exception> expected) throws IOException {
        WebPFilter objWebPFilter = new WebPFilter();
        Bucket output = new ArrayBucket();
        try (
            InputStream inStream = input.getInputStream();
            OutputStream outStream = output.getOutputStream()
        ) {
            if (expected != null) {
                assertThrows(expected, () -> readFilter(objWebPFilter, inStream, outStream));
            } else {
                readFilter(objWebPFilter, inStream, outStream);
            }
        }
        return output;
    }

    private static void readFilter(WebPFilter objWebPFilter, InputStream inStream, OutputStream outStream) throws IOException {
    	objWebPFilter.readFilter(inStream, outStream, "", null, null, null);
    }
}
