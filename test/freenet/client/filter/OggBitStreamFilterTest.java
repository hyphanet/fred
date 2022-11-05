package freenet.client.filter;

import static org.junit.Assert.*;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;


import org.junit.Test;

public class OggBitStreamFilterTest {
	@Test
	public void testGetVorbisBitstreamFilter() throws IOException {
		DataInputStream input = new DataInputStream(getClass().getResourceAsStream("./ogg/vorbis_header.ogg"));
		OggPage page = OggPage.readPage(input);
		assertEquals(VorbisBitstreamFilter.class, getFilterClass(page));
		input.close();
	}

	@Test
	public void testGetTheoraBitStreamFilter() throws IOException { 
		DataInputStream input = new DataInputStream(getClass().getResourceAsStream("./ogg/theora_header.ogg"));
		OggPage page = OggPage.readPage(input);
		assertEquals(TheoraBitstreamFilter.class, getFilterClass(page));
		input.close();
	}
	@Test
	public void testGetFilterForInvalidFormat() throws IOException {
		InputStream input = getClass().getResourceAsStream("./ogg/invalid_header.ogg");
		DataInputStream dis = new DataInputStream(input);
		OggPage page = OggPage.readPage(dis);
		assertEquals(null, getFilterClass(page));
		input.close();
	}

	@Test
	public void testPagesOutOfOrderCausesException() throws IOException {
		DataInputStream input = new DataInputStream(getClass().getResourceAsStream("./ogg/pages_out_of_order.ogg"));
		OggPage page = OggPage.readPage(input);
		OggBitstreamFilter filter = new OggBitstreamFilter(page);
		page = OggPage.readPage(input);
		try {
			filter.parse(page);
			fail("Expected exception not caught");
		} catch(DataFilterException e) {}
		input.close();
	}

	private Class<? extends OggBitstreamFilter> getFilterClass(OggPage page) {
		OggBitstreamFilter filter = OggBitstreamFilter.getBitstreamFilter(page);
		if(filter != null) return filter.getClass();
		return null;
	}
}
