package freenet.client.filter;

import static freenet.client.filter.ResourceFileUtil.resourceToDataInputStream;
import static freenet.client.filter.ResourceFileUtil.resourceToOggPage;
import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;

import org.junit.Test;

public class OggPageTest {
	@Test
	public void testStripNonsenseInterruption() throws IOException {
		ByteArrayOutputStream actualDataStream = new ByteArrayOutputStream();
		ByteArrayOutputStream expectedDataStream = new ByteArrayOutputStream();
		try (DataInputStream input = resourceToDataInputStream("./ogg/nonsensical_interruption_filtered.ogg")) {
			readPages(expectedDataStream, input);
		}
		try (DataInputStream input = resourceToDataInputStream("./ogg/nonsensical_interruption.ogg")) {
			readPages(actualDataStream, input);
		}
		assertArrayEquals(expectedDataStream.toByteArray(), actualDataStream.toByteArray());
	}

	private static void readPages(ByteArrayOutputStream output, DataInputStream input) throws IOException {
		OggPage page = OggPage.readPage(input);
		if (page.headerValid()) {
			output.write(page.toArray());
		}
		page = OggPage.readPage(input);
		if (page.headerValid()) {
			output.write(page.toArray());
		}
	}

	@Test
	public void testChecksum() throws IOException {
		OggPage page = resourceToOggPage("./ogg/valid_checksum.ogg");
		assertTrue(page.headerValid());
	}

	@Test
	public void testInvalidChecksumInvalidates() throws IOException {
		OggPage page = resourceToOggPage("./ogg/invalid_checksum.ogg");
		assertFalse(page.headerValid());
	}
}
