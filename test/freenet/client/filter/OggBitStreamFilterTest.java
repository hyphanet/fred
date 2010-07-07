package freenet.client.filter;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

import junit.framework.Assert;
import junit.framework.TestCase;


public class OggBitStreamFilterTest extends TestCase{
	public void testGetVorbisBitstreamFilter() throws IOException {
		InputStream input = getClass().getResourceAsStream("./ogg/vorbis_header.ogg");
		DataInputStream dis = new DataInputStream(input);
		OggPage page = OggPage.readPage(dis);
		Assert.assertEquals(VorbisBitstreamFilter.class, getFilterClass(page));
	}

	public void testGetFilterForInvalidFormat() throws IOException {
		InputStream input = getClass().getResourceAsStream("./ogg/invalid_header.ogg");
		DataInputStream dis = new DataInputStream(input);
		OggPage page = OggPage.readPage(dis);
		Assert.assertEquals(null, getFilterClass(page));
	}

	public void testPagesOutOfOrderCausesException() throws IOException {
		DataInputStream input = new DataInputStream(getClass().getResourceAsStream("./ogg/pages_out_of_order.ogg"));
		OggPage page = OggPage.readPage(input);
		OggBitstreamFilter filter = new OggBitstreamFilter(page);
		page = OggPage.readPage(input);
		try {
			filter.parse(page);
			fail("Expected exception not caught");
		} catch(DataFilterException e) {}
	}

	private Class<? extends OggBitstreamFilter> getFilterClass(OggPage page) {
		OggBitstreamFilter filter = OggBitstreamFilter.getBitstreamFilter(page);
		if(filter != null) return filter.getClass();
		return null;
	}
}
