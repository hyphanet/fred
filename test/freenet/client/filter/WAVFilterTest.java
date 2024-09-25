package freenet.client.filter;

import static freenet.client.filter.ResourceFileUtil.resourceToBucket;
import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;

import org.junit.Test;

import freenet.support.api.Bucket;
import freenet.support.io.ArrayBucket;
import freenet.support.io.BucketTools;

/**
 * Unit test for (parts of) {@link WAVFilter}.
 */
public class WAVFilterTest {

	@Test
	public void testValidWAV() throws IOException {
		Bucket input = resourceToBucket("./wav/test.wav");
		Bucket output = filterWAV(input);

		//Filter should return the original
		assertEquals("Input and output should be the same length", input.size(), output.size());
		assertArrayEquals("Input and output are not identical", BucketTools.toByteArray(input), BucketTools.toByteArray(output));
	}
	
	private Bucket filterWAV(Bucket input) throws IOException {
		WAVFilter objWAVFilter = new WAVFilter();
		Bucket output = new ArrayBucket();
		try (
			InputStream inStream = input.getInputStream();
			OutputStream outStream = output.getOutputStream()
		) {
			objWAVFilter.readFilter(inStream, outStream, "", null, null, null);
		}
		return output;
	}
}
