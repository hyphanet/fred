package freenet.client.filter;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import org.junit.Test;

public class OggPageTest {
	@Test
	public void testStripNonsenseInterruption() throws IOException {
		InputStream badData = getClass().getResourceAsStream("./ogg/nonsensical_interruption.ogg");
		ByteArrayOutputStream filteredDataStream = new ByteArrayOutputStream();
		DataOutputStream output = new DataOutputStream(filteredDataStream);
		DataInputStream input = new DataInputStream(badData);
		OggPage page = OggPage.readPage(input);
		if(page.headerValid()) output.write(page.toArray());
		page = OggPage.readPage(input);
		if(page.headerValid()) output.write(page.toArray());
		byte[] filteredData = filteredDataStream.toByteArray();
		output.close();
		input.close();

		InputStream goodData = getClass().getResourceAsStream("./ogg/nonsensical_interruption_filtered.ogg");
		input = new DataInputStream(goodData);
		ByteArrayOutputStream expectedDataStream = new ByteArrayOutputStream();
		output = new DataOutputStream(expectedDataStream);
		page = OggPage.readPage(input);
		if(page.headerValid()) output.write(page.toArray());
		page = OggPage.readPage(input);
		if(page.headerValid()) output.write(page.toArray());
		byte[] expectedData = expectedDataStream.toByteArray();
		output.close();
		input.close();

		assertTrue(Arrays.equals(filteredData, expectedData));
	}

	@Test
	public void testChecksum() throws IOException {
		DataInputStream input = new DataInputStream(getClass().getResourceAsStream("./ogg/valid_checksum.ogg"));
		OggPage page = OggPage.readPage(input);
		assertTrue(page.headerValid());
		input.close();
	}

	@Test
	public void testInvalidChecksumInvalidates() throws IOException {
		DataInputStream input = new DataInputStream(getClass().getResourceAsStream("./ogg/invalid_checksum.ogg"));
		OggPage page = OggPage.readPage(input);
		assertFalse(page.headerValid());
		input.close();
	}
}
