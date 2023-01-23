package freenet.client.filter;

import static freenet.client.filter.ResourceFileUtil.testResourceFile;
import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;

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
		testResourceFile("./ogg/invalid_header.ogg", (input) -> {
			assertThrows(
				DataFilterException.class,
				() -> filter.readFilter(input, new ByteArrayOutputStream(), null, null, null, null)
			);
		});
	}

	@Test
	public void testValidSubPageStripped() throws IOException {
		testResourceFile("./ogg/contains_subpages.ogg", (input) -> {
			try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
				assertThrows(
					DataFilterException.class,
					() -> filter.readFilter(input, output, null, null, null, null)
				);
				assertArrayEquals(new byte[]{}, output.toByteArray());
			}
		});
	}

    /** the purpose of this test is to create the testoutputFile so you can check it with a video player. */
	@Test
	public void testFilterFfmpegEncodedVideoSegment() throws IOException {
		try (
			ByteArrayOutputStream unchangedData = new ByteArrayOutputStream();
			ByteArrayOutputStream output = new ByteArrayOutputStream()
		) {
			testResourceFile(
				"./ogg/36C3_-_opening--cc-by--c3voc--fem-ags-opensuse--ccc--filtered.ogv",
				(inputFileUnchanged) -> IOUtils.copy(inputFileUnchanged, unchangedData)
			);
			testResourceFile(
				"./ogg/36C3_-_opening--cc-by--c3voc--fem-ags-opensuse--ccc--orig.ogv",
				(input) -> {
					filter.readFilter(input, output, null, null, null, null);
					writeToTestOutputFile(output);
				}
			);
			assertArrayEquals(unchangedData.toByteArray(), output.toByteArray());
		}
	}

	private void writeToTestOutputFile(ByteArrayOutputStream output) throws IOException {
		URL resource = getClass().getResource(
			"./ogg/36C3_-_opening--cc-by--c3voc--fem-ags-opensuse--ccc--filtered-testoutput.ogv"
		);
		if (resource == null) {
			throw new RuntimeException("Test file is not found");
		}
		String testOutputFile = resource.getFile();
		System.out.println(testOutputFile);
		try (FileOutputStream newFileStream = new FileOutputStream(testOutputFile)) {
			output.writeTo(newFileStream);
		}
	}
}
