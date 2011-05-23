package freenet.client.filter;

import junit.framework.TestCase;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileNotFoundException;
import java.io.OutputStream;
import java.util.Arrays;

import freenet.support.api.Bucket;
import freenet.support.io.ArrayBucket;
import freenet.support.io.BucketTools;


public class BMPFilterTest extends TestCase {
	/** Tests image that contains an invalid header */
	public void testSmall() throws IOException {
		Bucket input = resourceToBucket("./bmp/small.bmp");
		filterImage(input, DataFilterException.class);
	}

	/** Tests image that contains an invalid header (0x65 0x66) */
	public void testOne() throws IOException {
		Bucket input = resourceToBucket("./bmp/one.bmp");
		filterImage(input, DataFilterException.class);
	}

	/** Tests image with invalid info header size (70) */
	public void testTwo() throws IOException {
		Bucket input = resourceToBucket("./bmp/two.bmp");
		filterImage(input, DataFilterException.class);
	}

	/** Tests image with invalid bitmap info header size (119) */
	public void testThree() throws IOException {
		Bucket input = resourceToBucket("./bmp/three.bmp");
		filterImage(input, DataFilterException.class);
	}

	/** Negative image dimensions */
	public void testFour() throws IOException {
		Bucket input = resourceToBucket("./bmp/four.bmp");
		filterImage(input, DataFilterException.class);
	}

	/** Invalid number of planes (26213) */
	public void testFive() throws IOException {
		Bucket input = resourceToBucket("./bmp/five.bmp");
		filterImage(input, DataFilterException.class);
	}

	/** Invalid bit depth (71) */
	public void testSix() throws IOException {
		Bucket input = resourceToBucket("./bmp/six.bmp");
		filterImage(input, DataFilterException.class);
	}

	/** Invalid bit depth (17944) */
	public void testSeven() throws IOException {
		Bucket input = resourceToBucket("./bmp/seven.bmp");
		filterImage(input, DataFilterException.class);
	}

	/** Tests image where header size + image data size doesn't match the file size */
	public void testEight() throws IOException {
		Bucket input = resourceToBucket("./bmp/eight.bmp");
		filterImage(input, DataFilterException.class);
	}

	/** Tests image with negative resolution */
	public void testNine() throws IOException {
		Bucket input = resourceToBucket("./bmp/nine.bmp");
		filterImage(input, DataFilterException.class);
	}

	/** Tests image that is shorter than expected */
	public void testTen() throws IOException {
		Bucket input = resourceToBucket("./bmp/ten.bmp");
		filterImage(input, DataFilterException.class);
	}

	/** Tests valid image */
	public void testOk() throws IOException {
		Bucket input = resourceToBucket("./bmp/ok.bmp");
		Bucket output = filterImage(input, null);

		//Filter should return the original
		assertEquals("Input and output should be the same length", input.size(), output.size());
		assertTrue("Input and output are not identical", Arrays.equals(BucketTools.toByteArray(input), BucketTools.toByteArray(output)));
	}

	private Bucket filterImage(Bucket input, Class<? extends Exception> expected) {
		BMPFilter objBMPFilter = new BMPFilter();
		Bucket output = new ArrayBucket();

		InputStream inStream;
		OutputStream outStream;
		try {
			inStream = input.getInputStream();
			outStream = output.getOutputStream();
		} catch (IOException e) {
			e.printStackTrace();
			fail("Caugth unexpected IOException: " + e);
			return null; //Convince the compiler that we won't continue
		}

		try {
			objBMPFilter.readFilter(inStream, outStream, "", null, null);

			if(expected != null) {
				fail("Filter didn't throw expected exception");
			}
		} catch (Exception e) {
			if((expected == null) || (!expected.equals(e.getClass()))) {
				//Exception is not expected nor a subclass of an expected exception
				e.printStackTrace();
				fail("Caugth unexpected exception: " + e.getClass() + ": " + e.getMessage());
			}
		}

		try {
			inStream.close();
			outStream.close();
		} catch(IOException e) {
			e.printStackTrace();
			fail("Caugth unexpected IOException: " + e);
			return null; //Convince the compiler that we won't continue
		}

		return output;
	}

	private Bucket resourceToBucket(String filename) throws IOException {
		InputStream is = getClass().getResourceAsStream(filename);
		if (is == null) throw new FileNotFoundException();
		Bucket ab = new ArrayBucket();
		BucketTools.copyFrom(ab, is, Long.MAX_VALUE);
		return ab;
	}
}
