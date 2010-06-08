package freenet.client.filter;

import junit.framework.TestCase;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileNotFoundException;

import freenet.l10n.NodeL10n;
import freenet.support.api.Bucket;
import freenet.support.io.ArrayBucket;
import freenet.support.io.BucketTools;
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
	
	public void testReadFilter()  {
		new NodeL10n();
		BMPFilter objBMPFilter=new BMPFilter();
		ab = new ArrayBucket();
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


			try {
				objBMPFilter.readFilter(ib.getInputStream(), new NullBucket().getOutputStream(), "", null, null);
				assertEquals(filename + " should be valid", expectedresult,0);
			} 
			catch (DataFilterException dfe) {

				assertEquals(filename + " should not be valid", expectedresult,1);
			}
			catch (IOException exp)
			{
				assertEquals(filename + " should not be valid", expectedresult,2);
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
