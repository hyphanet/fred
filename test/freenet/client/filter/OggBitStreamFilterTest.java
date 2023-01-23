package freenet.client.filter;

import static freenet.client.filter.ResourceFileUtil.testResourceFile;
import static org.junit.Assert.*;

import java.io.IOException;


import org.junit.Test;

public class OggBitStreamFilterTest {
	@Test
	public void testGetVorbisBitstreamFilter() throws IOException {
		testResourceFile("./ogg/vorbis_header.ogg", (input) -> {
			OggPage page = OggPage.readPage(input);
			assertEquals(VorbisBitstreamFilter.class, getFilterClass(page));
		});
	}

	@Test
	public void testGetTheoraBitStreamFilter() throws IOException {
		testResourceFile("./ogg/theora_header.ogg", (input) -> {
			OggPage page = OggPage.readPage(input);
			assertEquals(TheoraBitstreamFilter.class, getFilterClass(page));
		});
	}
	@Test
	public void testGetFilterForInvalidFormat() throws IOException {
		testResourceFile("./ogg/invalid_header.ogg", (input) -> {
			OggPage page = OggPage.readPage(input);
			assertNull(getFilterClass(page));
		});
	}

	@Test
	public void testPagesOutOfOrderCausesException() throws IOException {
		testResourceFile("./ogg/pages_out_of_order.ogg", (input) -> {
			OggPage filterPage = OggPage.readPage(input);
			OggBitstreamFilter filter = new OggBitstreamFilter(filterPage);
			OggPage page = OggPage.readPage(input);
			assertThrows(DataFilterException.class, ()-> filter.parse(page));
		});
	}

	private Class<? extends OggBitstreamFilter> getFilterClass(OggPage page) {
		OggBitstreamFilter filter = OggBitstreamFilter.getBitstreamFilter(page);
		if(filter != null) {
			return filter.getClass();
		}
		return null;
	}
}
