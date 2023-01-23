package freenet.client.filter;

import org.junit.Test;

import java.io.EOFException;
import java.io.IOException;

import static freenet.client.filter.ResourceFileUtil.testResourceFile;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class TheoraBitstreamFilterTest {

    @Test
    public void parseIdentificationHeaderTest() throws IOException {
        testResourceFile("./ogg/theora_header.ogg", (input) -> {
            OggPage page = OggPage.readPage(input);

            TheoraBitstreamFilter theoraBitstreamFilter = new TheoraBitstreamFilter(page);
            assertEquals(page.asPackets(), theoraBitstreamFilter.parse(page).asPackets());
        });
    }

    @Test
    public void parseTest() throws IOException {
        testResourceFile("./ogg/Infinite_Hands-2008-Thusnelda-2009-09-18.ogv", (input) -> {
            OggPage page = OggPage.readPage(input);
            int pageSerial = page.getSerial();
            TheoraBitstreamFilter theoraBitstreamFilter = new TheoraBitstreamFilter(page);

            while (true) {
                try {
                    if (page.getSerial() == pageSerial) {
                        theoraBitstreamFilter.parse(page);
                    }

                    page = OggPage.readPage(input);
                } catch (EOFException e) {
                    break;
                }
            }
        });
    }

    @Test
    public void parseInvalidHeaderTest() throws IOException {
        testResourceFile("./ogg/invalid_header.ogg", (input) -> {
            OggPage page = OggPage.readPage(input);
            TheoraBitstreamFilter theoraBitstreamFilter = new TheoraBitstreamFilter(page);
            assertThrows(UnknownContentTypeException.class, () -> theoraBitstreamFilter.parse(page));
        });
    }
}
