package freenet.client.filter;

import static freenet.client.filter.ResourceFileUtil.resourceToBucket;
import static org.junit.Assert.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
		Bucket output = filterWAV(input, null);

		//Filter should return the original
		assertEquals("Input and output should be the same length", input.size(), output.size());
		assertArrayEquals("Input and output are not identical", BucketTools.toByteArray(input), BucketTools.toByteArray(output));
	}

	// This file is WebP, not WAV!
	@Test
	public void testAnotherFile() throws IOException {
		Bucket input = resourceToBucket("./webp/test.webp");
		filterWAV(input, DataFilterException.class);
	}
	
	// There is just a JUNK chunk in the file
	@Test
	public void testFileJustJUNK() throws IOException {
		ByteBuffer buf = ByteBuffer.allocate(28)
			.order(ByteOrder.LITTLE_ENDIAN)
			.put(new byte[]{'R', 'I', 'F', 'F'})
			.putInt(20 /* file size */)
			.put(new byte[]{'W', 'A', 'V', 'E'})
			.put(new byte[]{'J', 'U', 'N', 'K'})
			.putInt(7 /* chunk size */)
			.putLong(0);

		Bucket input = new ArrayBucket(buf.array());
		filterWAV(input, DataFilterException.class);
	}

	// There is just a fmt chunk in the file, but no audio data
	@Test
	public void testFileNoData() throws IOException {
		ByteBuffer buf = ByteBuffer.allocate(36)
			.order(ByteOrder.LITTLE_ENDIAN)
			.put(new byte[]{'R', 'I', 'F', 'F'})
			.putInt(28 /* file size */)
			.put(new byte[]{'W', 'A', 'V', 'E'})
			.put(new byte[]{'f', 'm', 't', ' '})
			.putInt(16 /* chunk size */)
			.put(new byte[]{1, 0, 2, 0}) //format, nChannels
			.putInt(44100) // nSamplesPerSec
			.putInt(44100 * 4) // nAvgBytesPerSec
			.put(new byte[]{4, 0, 16, 0}); // nBlockAlign, wBitsPerSample

		Bucket input = new ArrayBucket(buf.array());
		filterWAV(input, DataFilterException.class);
	}
	
	private Bucket filterWAV(Bucket input, Class<? extends Exception> expected) throws IOException {
		WAVFilter objWAVFilter = new WAVFilter();
		Bucket output = new ArrayBucket();
		try (
			InputStream inStream = input.getInputStream();
			OutputStream outStream = output.getOutputStream()
		) {
			if (expected != null) {
				assertThrows(expected, () -> objWAVFilter.readFilter(inStream, outStream, "", null, null, null));
			} else {
				objWAVFilter.readFilter(inStream, outStream, "", null, null, null);
			}
		}
		return output;
	}
}
