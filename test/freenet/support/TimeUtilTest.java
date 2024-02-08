/*
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package freenet.support;

import static java.util.Calendar.MILLISECOND;

import static org.junit.Assert.*;

import java.text.ParseException;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

import org.junit.Before;
import org.junit.Test;

/**
 * Test case for {@link freenet.support.TimeUtil} class.
 * 
 * @author Alberto Bacchelli &lt;sback@freenetproject.org&gt;
 */
public class TimeUtilTest {

	//1w+1d+1h+1m+1s+1ms
	private final long oneForTermLong = 694861001;
	
	@Before
	public void setUp() throws Exception {
		Locale.setDefault(Locale.US);
	}
	
	/**
	 * Tests formatTime(long,int,boolean) method
	 * trying the biggest long value
	 */
	@Test
	public void testFormatTime_LongIntBoolean_MaxValue() {
		String expectedForMaxLongValue = "15250284452w3d7h12m55.807s";
		assertEquals(
			expectedForMaxLongValue,
			TimeUtil.formatTime(Long.MAX_VALUE,6,true)
		);
	}

	/**
	 * Tests formatTime(long,int) method
	 * trying the biggest long value
	 */
	@Test
	public void testFormatTime_LongInt() {
		String expectedForMaxLongValue = "15250284452w3d7h12m55s";
		assertEquals(
			expectedForMaxLongValue,
			TimeUtil.formatTime(Long.MAX_VALUE,6)
		);
	}
	
	/**
	 * Tests formatTime(long) method
	 * trying the biggest long value
	 */
	@Test
	public void testFormatTime_Long() {
		//it uses two terms by default
		String expectedForMaxLongValue = "15250284452w3d";
		assertEquals(
			expectedForMaxLongValue,
			TimeUtil.formatTime(Long.MAX_VALUE)
		);
	}
	
	/**
	 * Tests formatTime(long) method
	 * using known values.
	 * They could be checked using Google Calculator
	 * http://www.google.com/intl/en/help/features.html#calculator
	 */
	@Test
	public void testFormatTime_KnownValues() {
		String[][] valAndExpected = {
				//one week
				{"604800000","1w"},	
				//one day
				{"86400000","1d"},	
				//one hour
				{"3600000","1h"},	
				//one minute
				{"60000","1m"},		
				//one second
				{"1000","1s"}		
		};
		for (String[] pair : valAndExpected) {
			assertEquals(
				pair[1],
				TimeUtil.formatTime(Long.parseLong(pair[0]))
			);
		}
	}
	
	/**
	 * Tests formatTime(long,int) method
	 * using a long value that generate every possible
	 * term kind. It tests if the maxTerms arguments
	 * works correctly
	 */
	@Test
	public void testFormatTime_LongIntBoolean_maxTerms() {
		String[] valAndExpected = {
			//0 terms
			"",
			//1 term
			"1w",
			//2 terms
			"1w1d",
			//3 terms
			"1w1d1h",
			//4 terms
			"1w1d1h1m",
			//5 terms
			"1w1d1h1m1s",
			//6 terms
			"1w1d1h1m1.001s"
		};
		for (int maxTerms = 0; maxTerms < valAndExpected.length; maxTerms++) {
			assertEquals(
				valAndExpected[maxTerms],
				TimeUtil.formatTime(oneForTermLong, maxTerms, true)
			);
		}
	}
	
	/**
	 * Tests formatTime(long,int) method
	 * using one millisecond time interval. 
	 * It tests if the withSecondFractions argument
	 * works correctly
	 */
	@Test
	public void testFormatTime_LongIntBoolean_milliseconds() {
		long methodValue = 1;	//1ms
		assertEquals("0s", TimeUtil.formatTime(methodValue,6,false));
		assertEquals("0.001s", TimeUtil.formatTime(methodValue,6,true));
	}
	
	/**
	 * Tests formatTime(long,int) method
	 * using a long value that generate every possible
	 * term kind. It tests if the maxTerms arguments
	 * works correctly
	 */
	@Test
	public void testFormatTime_LongIntBoolean_tooManyTerms() {	
		assertThrows(
			"Expected exception was not thrown for invalid maxTerms parameter",
			IllegalArgumentException.class,
			() -> TimeUtil.formatTime(oneForTermLong,7)
		);
	}

	/** Tests {@link TimeUtil#setTimeToZero(Date)} */
	@Test
	public void testSetTimeToZero() {
		// Test whether zeroing doesn't happen when it needs not to.
		
		GregorianCalendar c = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
		c.set(2015, 0 /* 0-based! */, 01, 00, 00, 00);
		c.set(MILLISECOND, 0);
		
		Date original = c.getTime();
		Date zeroed = TimeUtil.setTimeToZero(original);
		
		assertEquals(original, zeroed);
		// Date objects are mutable so their recycling is discouraged, check for it
		assertNotSame(original, zeroed);
		
		// Test whether zeroing happens when it should.
		
		c.set(2014, 12 - 1 /* 0-based! */, 31, 23, 59, 59);
		c.set(MILLISECOND, 999);
		original = c.getTime();
		Date originalBackup = (Date)original.clone();
		
		c.set(2014, 12 - 1 /* 0-based! */, 31, 00, 00, 00);
		c.set(MILLISECOND, 0);
		Date expected = c.getTime();
		
		zeroed = TimeUtil.setTimeToZero(original);
		
		assertEquals(expected, zeroed);
		assertNotSame(original, zeroed);
		// Check for bogus tampering with original object
		assertEquals(originalBackup, original);
	}

	@Test
	public void testToMillis_oneForTermLong() {
		assertEquals(TimeUtil.toMillis("1w1d1h1m1.001s"), oneForTermLong);
	}

	@Test
	public void testToMillis_maxLong() {
		assertEquals(TimeUtil.toMillis("15250284452w3d7h12m55.807s"), Long.MAX_VALUE);
	}

	@Test
	public void testToMillis_minLong() {
		assertEquals(TimeUtil.toMillis("-15250284452w3d7h12m55.808s"), Long.MIN_VALUE);
	}

	@Test
	public void testToMillis_empty() {
		assertEquals(TimeUtil.toMillis(""), 0);
		assertEquals(TimeUtil.toMillis("-"), 0);
	}

	@Test
	public void testToMillis_unknownFormat() {
		assertThrows(
			"Expected exception was not thrown for invalid time interval parameter",
			NumberFormatException.class,
			() -> TimeUtil.toMillis("15250284452w3q7h12m55.807s")
		);
	}

	@Test
	public void parseHttpDateTime() {
		Object[][] dateTimeSamples = {
			{"Sun, 06 Nov 1994 08:49:37 GMT", ZonedDateTime.of(1994, 11, 6, 8, 49, 37, 0, ZoneOffset.UTC)},
			{"Sun, 06 Nov 1994 08:49:37 UTC", ZonedDateTime.of(1994, 11, 6, 8, 49, 37, 0, ZoneOffset.UTC)},
			{"Sun, 06 Nov 1994 08:49:37 ABC", ZonedDateTime.of(1994, 11, 6, 8, 49, 37, 0, ZoneOffset.UTC)},
			{"Some text: Sun, 06 Nov 1994 08:49:37 <Some text>", ZonedDateTime.of(1994, 11, 6, 8, 49, 37, 0, ZoneOffset.UTC)},
			{"Sun, 06 Nov 1994 08:49:37", ZonedDateTime.of(1994, 11, 6, 8, 49, 37, 0, ZoneOffset.UTC)},
			{"Sun, 1994 Nov 6 08:49:37 GMT", ZonedDateTime.of(1994, 11, 6, 8, 49, 37, 0, ZoneOffset.UTC)},
			{"Sunday, 06-Nov-94 08:49:37 GMT", ZonedDateTime.of(1994, 11, 6, 8, 49, 37, 0, ZoneOffset.UTC)},
			{"Monday, 30-Jan-23 08:49:37 GMT", ZonedDateTime.of(2023, 1, 30, 8, 49, 37, 0, ZoneOffset.UTC)},
			{"Monday, 07 Nov 1994 08:49:37 GMT", ZonedDateTime.of(1994, 11, 7, 8, 49, 37, 0, ZoneOffset.UTC)},
			{"Tue Nov  8 08:49:37 1994", ZonedDateTime.of(1994, 11, 8, 8, 49, 37, 0, ZoneOffset.UTC)},
			{"Wed, 21.Oct.2015 07:28:00 GMT", ZonedDateTime.of(2015, 10, 21, 7, 28, 0, 0, ZoneOffset.UTC)},
			{"Thu, 01 Jan 1970 00:00:00 UTC", ZonedDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)},
			{"Fri, 15-Jan-2021 22:23:01 GMT", ZonedDateTime.of(2021, 1, 15, 22, 23, 1, 0, ZoneOffset.UTC)},
			{"Saturday, 15/January/2022 09:55:01 PST", ZonedDateTime.of(2022, 1, 15, 9, 55, 1, 0, ZoneId.of("PST", ZoneId.SHORT_IDS))},
			{"Sunday, 22 August 99 06:30:07", ZonedDateTime.of(1999, 8, 22, 6, 30, 7, 0, ZoneOffset.UTC)},
			{"2008-08-08T08:08:08", ZonedDateTime.of(2008, 8, 8, 8, 8, 8, 0, ZoneOffset.UTC)},
			{"2009-09-09T09:09:09+00:00", ZonedDateTime.of(2009, 9, 9, 9, 9, 9, 0, ZoneOffset.UTC)},
			{"2010-10-10T10:10:10.719922211-04:00", ZonedDateTime.of(2010, 10, 10, 10, 10, 10, 719922211, ZoneOffset.ofHours(-4))},
			{"2010-10-10T10:10:10.719922211", ZonedDateTime.of(2010, 10, 10, 10, 10, 10, 719922211, ZoneOffset.UTC)},
			{"2011-11-11T11:11:11.123+02:30", ZonedDateTime.of(2011, 11, 11, 11, 11, 11, 123000000, ZoneOffset.ofHoursMinutes(2, 30))},
			{"2011-12-03T10:15:30Z", ZonedDateTime.of(2011, 12, 3, 10, 15, 30, 0, ZoneOffset.UTC)},
			{"2011-12-03 10:15:30Z", ZonedDateTime.of(2011, 12, 3, 10, 15, 30, 0, ZoneOffset.UTC)},
			{"2011-12-03 10:15:30", ZonedDateTime.of(2011, 12, 3, 10, 15, 30, 0, ZoneOffset.UTC)},
			{"2011-12-03 10:15:30+01:00", ZonedDateTime.of(2011, 12, 3, 10, 15, 30, 0, ZoneOffset.ofHours(1))},
			{"2011-12-03 10:15:30-03:00", ZonedDateTime.of(2011, 12, 3, 10, 15, 30, 0, ZoneOffset.ofHours(-3))},
			{"Fri, 2013-DEC-13 13:13:13 GMT", ZonedDateTime.of(2013, 12, 13, 13, 13, 13, 0, ZoneOffset.UTC)},
			{"Tue Apr 12 09:45:14 2016", ZonedDateTime.of(2016, 4, 12, 9, 45, 14, 0, ZoneOffset.UTC)},
			{"Tue Aug 19 1975 23:15:30 GMT+0100 (Western European Summer Time)", ZonedDateTime.of(1975, 8, 19, 23, 15, 30, 0, ZoneOffset.ofHours(1))},
			{"Tue Aug 19 1975 23:15:30 +0100 (Western European Summer Time)", ZonedDateTime.of(1975, 8, 19, 23, 15, 30, 0, ZoneOffset.ofHours(1))},
			{"Tue Aug 19 +1975 23:15:30 +0100 (Western European Summer Time)", ZonedDateTime.of(1975, 8, 19, 23, 15, 30, 0, ZoneOffset.ofHours(1))},
			{"Tue Aug-19 -1975 23:15:30 -0100 (Western European Summer Time)", ZonedDateTime.of(1975, 8, 19, 23, 15, 30, 0, ZoneOffset.ofHours(-1))},
			{"Tue Aug-19-1975 23:15:30 -0100 (Western European Summer Time)", ZonedDateTime.of(1975, 8, 19, 23, 15, 30, 0, ZoneOffset.ofHours(-1))},
			{"Tue+Aug+19+1975+23:15:30+GMT+01:00 (Western European Summer Time)", ZonedDateTime.of(1975, 8, 19, 23, 15, 30, 0, ZoneOffset.ofHours(1))},
			{"Tue+Aug+19+1975+23:15:30+GMT-01:00 (Western European Summer Time)", ZonedDateTime.of(1975, 8, 19, 23, 15, 30, 0, ZoneOffset.ofHours(-1))},
			{"Tue Aug-19-1975 23:15:30 GMT+0100 (Western European Summer Time)", ZonedDateTime.of(1975, 8, 19, 23, 15, 30, 0, ZoneOffset.ofHours(1))},
			{"Tue Aug-19-1975 23:15:30 GMT+023015 (Western European Summer Time)", ZonedDateTime.of(1975, 8, 19, 23, 15, 30, 0, ZoneOffset.ofHoursMinutesSeconds(2, 30, 15))},
			{"Tue Aug-19-1975 23:15:30 GMT+02:30:15 (Western European Summer Time)", ZonedDateTime.of(1975, 8, 19, 23, 15, 30, 0, ZoneOffset.ofHoursMinutesSeconds(2, 30, 15))},
			{"Wednesday, Aug 20 1975 12:00:01 GMT+08:15", ZonedDateTime.of(1975, 8, 20, 12, 0, 1, 0, ZoneOffset.ofHoursMinutes(8, 15))}
		};

		for (Object[] sample: dateTimeSamples) {
			String dateTimeText = (String) sample[0];
			ZonedDateTime validDateTime = (ZonedDateTime) sample[1];
			assertEquals(
				"Result is not equal for input datetime text: '" + dateTimeText + "'",
				validDateTime.toInstant(),
				TimeUtil.parseHttpDateTime(dateTimeText).toInstant()
			);
		}

		List<String> invalidDateTimeSamples = Arrays.asList(
			// invalid day
			"Saturday, 22 August 99 06:30:07",
			// cannot detect month or day
			"Sun, 1994 11 06 Nov 08:49:37 GMT",
			"08/22/2006 06:30",
			"08/22/2006 06:30 AM",
			"08/22/2006 6:30",
			"08/22/2006 08:49:37",
			"2006 08 22  08:49:37",
			"30 Feb 1970 00:00:00 UTC"
		);
		for (String dateTimeText: invalidDateTimeSamples) {
			assertThrows(
				"Parse method should throw exception for invalid input datetime text: '" + dateTimeText + "'",
				DateTimeParseException.class,
				() -> TimeUtil.parseHttpDateTime(dateTimeText)
			);
		}
	}
}
