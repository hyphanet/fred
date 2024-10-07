package freenet.client.filter;

import static org.junit.Assert.*;
import static freenet.client.filter.ResourceFileUtil.resourceToBucket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

import freenet.support.api.Bucket;
import freenet.support.io.ArrayBucket;
import freenet.support.io.ArrayBucketFactory;
import freenet.support.io.BucketTools;

/**
 * Unit test for (parts of) {@link WebPFilter}.
 */
public class WebPFilterTest {

	/**
	 * Tests file without media chunk
	 */
	@Test
	public void testNoChunkFile() throws IOException {
		ByteBuffer buf = ByteBuffer.allocate(12)
			.order(ByteOrder.LITTLE_ENDIAN)
			.put(new byte[]{'R', 'I', 'F', 'F'})
			.putInt(4 /* file size */)
			.put(new byte[]{'W', 'E', 'B', 'P'});

		Bucket input = new ArrayBucket(buf.array());
		filterImage(input, DataFilterException.class);
	}

	/**
	 * Tests file with too short VP8 chunk
	 */
	@Test
	public void testFileEOF() throws IOException {
		ByteBuffer buf = ByteBuffer.allocate(33)
			.order(ByteOrder.LITTLE_ENDIAN)
			.put(new byte[]{'R', 'I', 'F', 'F'})
			.putInt(0x1308 /* file size */)
			.put(new byte[]{'W', 'E', 'B', 'P'})
			.put(new byte[]{'V', 'P', '8', ' '})
			.putInt(0x12FC /* chunk size */)
			.putLong(((long)0x2a019d << 24 /* frame tag */) | (1 << 4 /* show_frame=1 */));

		Bucket input = new ArrayBucket(buf.array());
		filterImage(input, DataFilterException.class);
	}

	/**
	 * Tests file with just only a JUNK chunk
	 */
	@Test
	public void testFileJustJUNK() throws IOException {
		ByteBuffer buf = ByteBuffer.allocate(28)
			.order(ByteOrder.LITTLE_ENDIAN)
			.put(new byte[]{'R', 'I', 'F', 'F'})
			.putInt(20 /* file size */)
			.put(new byte[]{'W', 'E', 'B', 'P'})
			.put(new byte[]{'J', 'U', 'N', 'K'})
			.putInt(7 /* chunk size */)
			.putLong(0);

		Bucket input = new ArrayBucket(buf.array());
		filterImage(input, DataFilterException.class);
	}

	/**
	 * Tests file with chunk size of 0x7fffff00
	 */
	@Test
	public void testTooBig() throws IOException {
		ByteBuffer buf = ByteBuffer.allocate(32)
			.order(ByteOrder.LITTLE_ENDIAN)
			.put(new byte[]{'R', 'I', 'F', 'F'})
			.putInt(0x7fffff0c /* file size */)  // 2 GiB
			.put(new byte[]{'W', 'E', 'B', 'P'})
			.put(new byte[]{'V', 'P', '8', ' '})
			.putInt(0x7fffff00 /* chunk size */)  // 2 GiB
			.putLong(((long)0x2a019d << 24 /* frame tag */) | (1 << 4 /* show_frame=1 */));

		Bucket input = new ArrayBucket(buf.array());
		filterImage(input, DataFilterException.class);
	}

	/**
	 * Tests file with file size of 0
	 */
	@Test
	public void fileSizeTooSmall() throws IOException {
		ByteBuffer buf = ByteBuffer.allocate(32)
			.order(ByteOrder.LITTLE_ENDIAN)
			.put(new byte[]{'R', 'I', 'F', 'F'})
			.putInt(0 /* file size */)  // Empty RIFF file, promise!
			.put(new byte[]{'W', 'E', 'B', 'P'})
			.put(new byte[]{'V', 'P', '8', ' '})
			.putInt(12 /* chunk size */)
			.putLong(((long)0x2a019d << 24 /* frame tag */) | (1 << 4 /* show_frame=1 */));

		Bucket input = new ArrayBucket(buf.array());
		filterImage(input, DataFilterException.class);
	}
	
	/**
	 * Tests valid image (lossy image from libwebp 1.4)
	 */
	@Test
	public void testValidImageLossy() throws IOException {
		Bucket input = resourceToBucket("./webp/test.webp");
		Bucket output = filterImage(input, null);

		//Filter should return the original
		assertEquals("Input and output should be the same length", input.size(), output.size());
		assertArrayEquals("Input and output are not identical", BucketTools.toByteArray(input), BucketTools.toByteArray(output));
	}

	/**
	 * Tests valid image (lossy image with alpha channel from https://developers.google.com/speed/webp/gallery2 “Yellow Rose”)
	 */
	@Test
	public void testValidImageLossyAlpha() throws IOException {
		Bucket input = resourceToBucket("./webp/1_webp_a.webp");
		Bucket output = filterImage(input, null);

		//Filter should return the original
		assertEquals("Input and output should be the same length", input.size(), output.size());
		assertArrayEquals("Input and output are not identical", BucketTools.toByteArray(input), BucketTools.toByteArray(output));
	}
	
	/**
	 * Tests data after valid image
	 */
	@Test
	public void testDataAfterEOF() throws IOException {
		ArrayBucketFactory bf = new ArrayBucketFactory();
		Bucket inputValid = resourceToBucket("./webp/test.webp");
		Bucket input = BucketTools.pad(inputValid, (int)inputValid.size() + 1, bf, (int) inputValid.size());
		assertEquals("Input size is wrong", inputValid.size() + 1l, input.size());
		filterImage(input, DataFilterException.class);
	}

	private Bucket filterImage(Bucket input, Class<? extends Exception> expected) throws IOException {
		WebPFilter objWebPFilter = new WebPFilter();
		Bucket output = new ArrayBucket();
		try (
			InputStream inStream = input.getInputStream();
			OutputStream outStream = output.getOutputStream()
		) {
			if (expected != null) {
				assertThrows(expected, () -> readFilter(objWebPFilter, inStream, outStream));
			} else {
				readFilter(objWebPFilter, inStream, outStream);
			}
		}
		return output;
	}

	private static void readFilter(WebPFilter objWebPFilter, InputStream inStream, OutputStream outStream) throws IOException {
		objWebPFilter.readFilter(inStream, outStream, "", null, null, null);
	}
}
