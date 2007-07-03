package freenet.support;

import junit.framework.TestCase;

public class TimeUtilTest extends TestCase {

	private long oneForTermLong = 694861001;	//1w+1d+1h+1m+1s+1ms
	
	/**
	 * Tests formatTime(long,int,boolean) method
	 * trying the biggest long value
	 */
	public void testFormatTime_LongIntBoolean_MaxValue() {
		String expectedForMaxLongValue = "15250284452w3d7h12m55.807s";
		assertEquals(TimeUtil.formatTime(Long.MAX_VALUE,6,true),expectedForMaxLongValue);
	}

	/**
	 * Tests formatTime(long,int) method
	 * trying the biggest long value
	 */
	public void testFormatTime_LongInt() {
		String expectedForMaxLongValue = "15250284452w3d7h12m55s";
		assertEquals(TimeUtil.formatTime(Long.MAX_VALUE,6),expectedForMaxLongValue);
	}
	
	/**
	 * Tests formatTime(long) method
	 * trying the biggest long value
	 */
	public void testFormatTime_Long() {
		String expectedForMaxLongValue = "15250284452w3d";	//it uses two terms by default
		assertEquals(TimeUtil.formatTime(Long.MAX_VALUE),expectedForMaxLongValue);
	}
	
	/**
	 * Tests formatTime(long) method
	 * using known values.
	 * They could be checked using Google Calculator
	 * http://www.google.com/intl/en/help/features.html#calculator
	 */
	public void testFormatTime_KnownValues() {
		Long methodLong;
		String[][] valAndExpected = {
				{"604800000","1w"},	//one week
				{"86400000","1d"},	//one day
				{"3600000","1h"},	//one hour
				{"60000","1m"},		//one minute
				{"1000","1s"}		//one second
		};
		for(int i = 0; i < valAndExpected.length; i++) {
			methodLong = Long.valueOf(valAndExpected[i][0]);
			assertEquals(TimeUtil.formatTime(methodLong.longValue()),valAndExpected[i][1]); }	
	}
	
	/**
	 * Tests formatTime(long,int) method
	 * using a long value that generate every possible
	 * term kind. It tests if the maxTerms arguments
	 * works correctly
	 */
	public void testFormatTime_LongIntBoolean_maxTerms() {
		String[] valAndExpected = {
				"",					//0 terms
				"1w",				//1 term
				"1w1d",				//2 terms
				"1w1d1h",			//3 terms
				"1w1d1h1m",			//4 terms
				"1w1d1h1m1s",		//5 terms
				"1w1d1h1m1.001s"	//6 terms
		};
		for(int i = 0; i < valAndExpected.length; i++)
			assertEquals(TimeUtil.formatTime(oneForTermLong,i,true),valAndExpected[i]);
	}
	
	/**
	 * Tests formatTime(long,int) method
	 * using one millisecond time interval. 
	 * It tests if the withSecondFractions argument
	 * works correctly
	 */
	public void testFormatTime_LongIntBoolean_milliseconds() {
		long methodValue = 1;	//1ms
		assertEquals(TimeUtil.formatTime(methodValue,6,false),"");
		assertEquals(TimeUtil.formatTime(methodValue,6,true),"0.001s");
	}
	
	/**
	 * Tests formatTime(long,int) method
	 * using a long value that generate every possible
	 * term kind. It tests if the maxTerms arguments
	 * works correctly
	 */
	public void testFormatTime_LongIntBoolean_tooManyTerms() {	
		try {
			TimeUtil.formatTime(oneForTermLong,7);
			fail("Expected IllegalArgumentException not thrown"); }
		catch (IllegalArgumentException anException) {
			assertNotNull(anException); }
	}

}
