package freenet.client.filter;

import static freenet.client.filter.ResourceFileUtil.testResourceFile;
import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;

import org.junit.Test;

public class OggPageTest {
	@Test
	public void testStripNonsenseInterruption() throws IOException {
		try (
			ByteArrayOutputStream actualDataStream = new ByteArrayOutputStream();
			ByteArrayOutputStream expectedDataStream = new ByteArrayOutputStream()
		) {
			testResourceFile(
				"./ogg/nonsensical_interruption_filtered.ogg",
				(input) -> readPages(expectedDataStream, input)
			);
			testResourceFile(
				"./ogg/nonsensical_interruption.ogg",
				(input) -> readPages(actualDataStream, input)
			);
			assertArrayEquals(expectedDataStream.toByteArray(), actualDataStream.toByteArray());
		}
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
		testResourceFile("./ogg/valid_checksum.ogg", (input) -> {
			OggPage page = OggPage.readPage(input);
			assertTrue(page.headerValid());
		});
	}

	@Test
	public void testInvalidChecksumInvalidates() throws IOException {
		testResourceFile("./ogg/invalid_checksum.ogg", (input) -> {
			OggPage page = OggPage.readPage(input);
			assertFalse(page.headerValid());
		});
	}
}
