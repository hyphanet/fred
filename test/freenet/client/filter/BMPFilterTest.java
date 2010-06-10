package freenet.client.filter;

import junit.framework.TestCase;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileNotFoundException;
import java.io.OutputStream;

import freenet.l10n.NodeL10n;
import freenet.support.api.Bucket;
import freenet.support.io.ArrayBucket;
import freenet.support.io.BucketTools;
import freenet.support.io.Closer;
import freenet.support.io.NullBucket;


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
	ArrayBucket ab;

	public void setUp() {
		new NodeL10n();
	}
	
	public void testReadFilter() throws IOException {
		new NodeL10n();
		BMPFilter objBMPFilter=new BMPFilter();
		ab = new ArrayBucket();
		Bucket output = new ArrayBucket();
		for (Object[] test : testImages) {
			String filename=(String) test[0];
			int expectedresult = Integer.parseInt(test[1].toString());
			Bucket ib;
			try {
				ib = resourceToBucket(filename);

			} 	

			catch (FileNotFoundException e) {
				assertFalse(filename +" not found in BMP test", false);
				continue;
			}
			catch (IOException e) {
				System.out.println("IOException during reading "+filename);
				continue;
			}


			InputStream inputStream = null;
			OutputStream outputStream = null;
			try {
				inputStream = ib.getInputStream();
				outputStream = output.getOutputStream();
				objBMPFilter.readFilter(inputStream, outputStream, "", null, null);
				inputStream.close();
				outputStream.close();
				assertEquals(filename + " should be valid", expectedresult,0);
				assertEquals("Input and output should be the same length", ib.size(), output.size());
				assertEquals("Input and output should be identical", BucketTools.hash(ib), BucketTools.hash(output));
			} 
			catch (DataFilterException dfe) {

				assertEquals(filename + " should not be valid", expectedresult,1);
			}
			catch (IOException exp)
			{
				assertEquals(filename + " should not be valid", expectedresult,2);
			}
			finally {
				inputStream.close();
				outputStream.close();
			}

		}
	}



	protected Bucket resourceToBucket(String filename) throws IOException {
		InputStream is = getClass().getResourceAsStream(filename);
		if (is == null) throw new FileNotFoundException();
		ab.free();
		BucketTools.copyFrom(ab, is, Long.MAX_VALUE);
		return ab;
	}
}
