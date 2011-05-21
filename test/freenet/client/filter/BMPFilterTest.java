package freenet.client.filter;

import junit.framework.TestCase;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileNotFoundException;
import java.io.OutputStream;
import java.util.Arrays;

import freenet.l10n.NodeL10n;
import freenet.support.api.Bucket;
import freenet.support.io.ArrayBucket;
import freenet.support.io.BucketTools;


public class BMPFilterTest extends TestCase {

	static final int TESTOK = 0;
	static final int DATAFILTEREXCEPTION = 1;
	static final int IOEXCEPTION = 2;
	static Object[][]  testImages={
		{"./bmp/small.bmp",DATAFILTEREXCEPTION},
		{"./bmp/one.bmp",DATAFILTEREXCEPTION},
		{"./bmp/two.bmp",DATAFILTEREXCEPTION},
		{"./bmp/three.bmp",DATAFILTEREXCEPTION},
		{"./bmp/four.bmp",DATAFILTEREXCEPTION},
		{"./bmp/five.bmp",DATAFILTEREXCEPTION},
		{"./bmp/six.bmp",DATAFILTEREXCEPTION},
		{"./bmp/seven.bmp",DATAFILTEREXCEPTION},
		{"./bmp/eight.bmp",DATAFILTEREXCEPTION},
		{"./bmp/nine.bmp",DATAFILTEREXCEPTION},
		{"./bmp/ten.bmp",DATAFILTEREXCEPTION},
		{"./bmp/ok.bmp",TESTOK}

	};

	public void setUp() {
		new NodeL10n();
	}
	
	public void testReadFilter() throws IOException {
		new NodeL10n();
		for (Object[] test : testImages) {
			String filename=(String) test[0];
			int expectedresult = Integer.parseInt(test[1].toString());
			Bucket ib;
			ib = resourceToBucket(filename);

			if(expectedresult == TESTOK) {
				Bucket output = filterImage(ib, null);
				assertEquals(filename + " should be valid", expectedresult,0);
				assertEquals("Input and output should be the same length", ib.size(), output.size());
				assertTrue("Input and output are not identical", Arrays.equals(BucketTools.toByteArray(ib), BucketTools.toByteArray(output)));
			} else if(expectedresult == DATAFILTEREXCEPTION) {
				filterImage(ib, DataFilterException.class);
			} else if(expectedresult == IOEXCEPTION) {
				filterImage(ib, IOException.class);
			}
		}
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
			if((expected == null) || (!expected.isInstance(e))) {
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



	protected Bucket resourceToBucket(String filename) throws IOException {
		InputStream is = getClass().getResourceAsStream(filename);
		if (is == null) throw new FileNotFoundException();
		Bucket ab = new ArrayBucket();
		BucketTools.copyFrom(ab, is, Long.MAX_VALUE);
		return ab;
	}
}
