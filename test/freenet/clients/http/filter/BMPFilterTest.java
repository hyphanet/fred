package freenet.clients.http.filter;

import junit.framework.TestCase;
import java.io.IOException;
import java.io.InputStream;

import freenet.support.api.Bucket;
import freenet.support.io.ArrayBucket;
import freenet.support.io.ArrayBucketFactory;
import freenet.support.io.BucketTools;


public class BMPFilterTest extends TestCase {

	static Object[][]  testImages={
		{"./bmp/small.bmp",false},
		{"./bmp/one.bmp",false},
		{"./bmp/two.bmp",false},
		{"./bmp/three.bmp",false},
		{"./bmp/four.bmp",false},
		{"./bmp/five.bmp",false},
		{"./bmp/six.bmp",false},
		{"./bmp/seven.bmp",false},
		{"./bmp/eight.bmp",false},
		{"./bmp/nine.bmp",false},
		{"./bmp/ten.bmp",false},
		{"./bmp/ok.bmp",true}
		
	};
	
	
	public void testReadFilter() {
		BMPFilter objBMPFilter=new BMPFilter();
		
		for (Object[] test : testImages) {
			String filename=(String) test[0];
			boolean valid = (Boolean) test[1];
			Bucket ib;
		try {
				ib = resourceToBucket(filename);
				
			} 	
		
		catch (IOException e) {
				System.out.println(filename + " not found, test skipped");
				continue;
			}
			

		try {
				Bucket ob = objBMPFilter.readFilter(ib, new ArrayBucketFactory(), "", null, null);
				assertTrue(filename + " should " + (valid ? "" : "not ") + "be valid", valid);
				//System.out.println(filename+": File has passed the filter test");
			} 
			catch (DataFilterException dfe) {
				
				//System.out.println("DataFilterException: "+dfe.encodedTitle+":  "+dfe.rawTitle);
				assertFalse(filename + " should " + (valid ? "" : "not ") + "be valid", valid);
				
				
			}
			catch (IOException exp)
			{
				//System.out.println(filename+": IOException "+exp.getMessage());
				assertFalse(filename + " should " + (valid ? "" : "not ") + "be valid", valid);
			}	
			
		}
	}
	
		
	
	protected Bucket resourceToBucket(String filename) throws IOException {
		InputStream is = getClass().getResourceAsStream(filename);
		if (is == null) throw new java.io.FileNotFoundException();
		ArrayBucket ab = new ArrayBucket();
		BucketTools.copyFrom(ab, is, Long.MAX_VALUE);
		return ab;
	}
}
