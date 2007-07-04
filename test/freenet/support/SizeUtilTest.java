package freenet.support;

import junit.framework.TestCase;

public class SizeUtilTest extends TestCase {
	
	String[][] valAndExpected = {
			{"1","B"},									//one byte
			{"1024","KiB"},								//one kilobyte
			{"1048576","MiB"},							//one megabyte
			{"1073741824","GiB"},						//one gigabyte
			{"1099511627776","TiB"},					//one terabyte
			//{"1125899906842624","1.0 PiB"},			//one petabyte
			//{"1152921504606846976", "1.0 EiB"},		//one exabyte
			//{"1180591620717411303424", "1.0 ZiB"},	//one zettabyte
			//{"1208925819614629174706176","1.0 YiB"},	//one yottabyte
	};
	
	public void testFormatSizeLong() {
		Long methodLong;
		methodLong = Long.valueOf(valAndExpected[0][0]);
		assertEquals(SizeUtil.formatSize(methodLong.longValue()),"1 "+valAndExpected[0][1]);
		
		for(int i = 1; i < valAndExpected.length; i++) {
			methodLong = Long.valueOf(valAndExpected[i][0]);
			assertEquals(SizeUtil.formatSize(methodLong.longValue()),"1.0 "+valAndExpected[i][1]); }
	}

	/**
	 * Tests if formatSize(long) method
	 * works correctly with intermediate values
	 * (i.e. 1/4,1/2,3/4)
	 */
	public void testFormatSizeLong_WithIntermediateValues() {
		Long methodLong;
		String[] actualValue = {"1.0","1.25","1.5","1.75"};
		
		for(int i = 1; i < valAndExpected.length; i++) {
			methodLong = Long.valueOf(valAndExpected[i][0]);
			for(int j = 0; j < 4; j++)
				assertEquals(SizeUtil.formatSize(methodLong.longValue()+(methodLong.longValue()*j/4)),
						actualValue[j]+" "+valAndExpected[i][1]);
			}
	}

}
