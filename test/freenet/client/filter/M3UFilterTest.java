package freenet.client.filter;

import static org.junit.Assert.*;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import org.junit.Test;

import freenet.support.api.Bucket;
import freenet.support.io.ArrayBucket;

public class M3UFilterTest {
    protected static String[][] testPlaylists = {
        { "./m3u/safe.m3u", "./m3u/safe_madesafe.m3u" },
        { "./m3u/unsafe.m3u", "./m3u/unsafe_madesafe.m3u" },
    };

    private static final String SCHEME_HOST_PORT = "http://localhost:8888";
    private static final String BASE_KEY = "USK@0I8gctpUE32CM0iQhXaYpCMvtPPGfT4pjXm01oid5Zc,3dAcn4fX2LyxO6uCnWFTx-2HKZ89uruurcKwLSCxbZ4,AQACAAE/FakeM3UHostingFreesite/23/";
    private static final String BASE_URI = '/' + BASE_KEY;

    @Test
    public void testSuiteTest() throws IOException {
        M3UFilter filter = new M3UFilter();

        for (String[] test : testPlaylists) {
            String original = test[0];
            String correct = test[1];
            Bucket ibo;
            Bucket ibprocessed = new ArrayBucket();
            ArrayBucket ibc;
            ibo = ResourceFileUtil.resourceToBucket(original);
            ibc = ResourceFileUtil.resourceToBucket(correct);

            try {
                filter.readFilter(ibo.getInputStream(), ibprocessed.getOutputStream(), "UTF-8", null,
                    SCHEME_HOST_PORT, new GenericReadFilterCallback(new URI(BASE_URI), null, null, null));
                String result = ibprocessed.toString();

                assertEquals(
                    original + " should be filtered as " + correct + " but was filtered as\n" + result + "\ninstead of the correct\n" + bucketToString(ibc),
                    bucketToString(ibc),
                    result
                );
            } catch (DataFilterException dfe) {
                fail("Filtering " + original + " failed");
            } catch (URISyntaxException use) {
                fail("Creating URI from BASE_URI " + BASE_URI + " failed");
            }
        }
    }

    protected String bucketToString(ArrayBucket bucket) throws IOException {
        return new String(bucket.toByteArray());
    }

}
