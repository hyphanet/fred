package freenet.client.filter;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URL;
import java.util.Arrays;

import org.apache.commons.compress.utils.IOUtils;
import org.junit.Before;
import org.junit.Test;

public class OggFilterTest {
	private OggFilter filter;

	@Before
	public void setUp() {
		filter = new OggFilter();
	}

	@Test
	public void testEmptyOutputRaisesException() throws IOException {
		DataInputStream input = new DataInputStream(getClass().getResourceAsStream("./ogg/invalid_header.ogg"));
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try {
			filter.readFilter(input, output, null, null, null, null);
			fail("Expected Exception not caught. Output size: "+output.toByteArray().length);
		} catch(DataFilterException e) {}
	}

	@Test
	public void testValidSubPageStripped() throws IOException, DataFilterException {
		DataInputStream input = new DataInputStream(getClass().getResourceAsStream("./ogg/contains_subpages.ogg"));
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try {
			filter.readFilter(input, output, null, null, null, null);
		} catch(DataFilterException e) {}
		assertTrue(Arrays.equals(new byte[]{}, output.toByteArray()));
		input.close();
		output.close();
	}

    /** the purpose of this test is to create the testoutputFile so you can check it with a video player. */
	@Test
	public void testFilterFfmpegEncodedVideoSegment() throws IOException, DataFilterException {
        String testoutputFile = getClass().getResource(
	 			"./ogg/36C3_-_opening--cc-by--c3voc--fem-ags-opensuse--ccc--filtered-testoutput.ogv")
					.getFile();
		DataInputStream inputFileUnchanged = new DataInputStream(getClass().getResourceAsStream(
				"./ogg/36C3_-_opening--cc-by--c3voc--fem-ags-opensuse--ccc--filtered.ogv"));
		ByteArrayOutputStream unchangedData = new ByteArrayOutputStream();
		IOUtils.copy(inputFileUnchanged, unchangedData);
		DataInputStream inputFileToParse = new DataInputStream(getClass().getResourceAsStream(
				"./ogg/36C3_-_opening--cc-by--c3voc--fem-ags-opensuse--ccc--orig.ogv"));
		DataInputStream input = inputFileToParse;
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try {
			filter.readFilter(input, output, null, null, null, null);
			FileOutputStream newFileStream = new FileOutputStream(testoutputFile);
			System.out.println(testoutputFile);
			output.writeTo(newFileStream);
			newFileStream.close();
		} catch(DataFilterException e) {}
		assertTrue(Arrays.equals(unchangedData.toByteArray(), output.toByteArray()));
		input.close();
		output.close();
	}
}
