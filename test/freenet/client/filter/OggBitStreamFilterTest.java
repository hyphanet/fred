package freenet.client.filter;

import static freenet.client.filter.ResourceFileUtil.resourceToDataInputStream;
import static freenet.client.filter.ResourceFileUtil.resourceToOggPage;
import static org.junit.Assert.*;

import java.io.DataInputStream;
import java.io.IOException;


import org.junit.Test;

public class OggBitStreamFilterTest {
    @Test
    public void testGetVorbisBitstreamFilter() throws IOException {
		OggPage page = resourceToOggPage("./ogg/vorbis_header.ogg");
		assertEquals(VorbisBitstreamFilter.class, getFilterClass(page));
    }

    @Test
    public void testGetTheoraBitStreamFilter() throws IOException {
        OggPage page = resourceToOggPage("./ogg/theora_header.ogg");
        assertEquals(TheoraBitstreamFilter.class, getFilterClass(page));
    }

    @Test
    public void testGetFilterForInvalidFormat() throws IOException {
        OggPage page = resourceToOggPage("./ogg/invalid_header.ogg");
        assertNull(getFilterClass(page));
    }

    @Test
    public void testPagesOutOfOrderCausesException() throws IOException {
        try (DataInputStream input = resourceToDataInputStream("./ogg/pages_out_of_order.ogg")) {
            OggPage filterPage = OggPage.readPage(input);
            OggBitstreamFilter filter = new OggBitstreamFilter(filterPage);
            OggPage page = OggPage.readPage(input);
            assertThrows(DataFilterException.class, () -> filter.parse(page));
        }
    }

    private Class<? extends OggBitstreamFilter> getFilterClass(OggPage page) {
        OggBitstreamFilter filter = OggBitstreamFilter.getBitstreamFilter(page);
        if (filter != null) {
            return filter.getClass();
        }
        return null;
    }
}
