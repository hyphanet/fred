/*
  TimeUtil.java / Freenet
  Copyright (C) 2005-2006 The Free Network project
  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

package freenet.support;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Time formatting utility.
 * Formats milliseconds into a week/day/hour/second/milliseconds string.
 */
public class TimeUtil {

	public static final TimeZone TZ_UTC = TimeZone.getTimeZone("UTC");

	/**
	 * It converts a given time interval into a 
	 * week/day/hour/second.milliseconds string.
	 * @param timeInterval interval to convert
	 * @param maxTerms the terms number to display
	 * (e.g. 2 means "h" and "m" if the time could be expressed in hour,
	 * 3 means "h","m","s" in the same example).
	 * The maximum terms number available is 6
	 * @param withSecondFractions if true it displays seconds.milliseconds
	 * @return the formatted String
	 */
    public static String formatTime(long timeInterval, int maxTerms, boolean withSecondFractions) {
        
    	if (maxTerms > 6 )
        	throw new IllegalArgumentException();
        
    	StringBuilder sb = new StringBuilder(64);
        long l = timeInterval;
        int termCount = 0;
        //
        if(l < 0) {
            sb.append('-');
            l = l * -1;
        }
        if( !withSecondFractions && l < 1000 ) {
            return "0s";
        }
        if(termCount >= maxTerms) {
            return sb.toString();
        }
        //
        long weeks = (l / (7L*24*60*60*1000));
        if (weeks > 0) {
            sb.append(weeks).append('w');
            termCount++;
            l = l - (weeks * (7L*24*60*60*1000));
        }
        if(termCount >= maxTerms) {
            return sb.toString();
        }
        //
        long days = (l / (24L*60*60*1000));
        if (days > 0) {
            sb.append(days).append('d');
            termCount++;
            l = l - (days * (24L*60*60*1000));
        }
        if(termCount >= maxTerms) {
          return sb.toString();
        }
        //
        long hours = (l / (60L*60*1000));
        if (hours > 0) {
            sb.append(hours).append('h');
            termCount++;
            l = l - (hours * (60L*60*1000));
        }
        if(termCount >= maxTerms) {
            return sb.toString();
        }
        //
        long minutes = (l / (60L*1000));
        if (minutes > 0) {
            sb.append(minutes).append('m');
            termCount++;
            l = l - (minutes * (60L*1000));
        }
        if(termCount >= maxTerms) {
            return sb.toString();
        }
        if(withSecondFractions && ((maxTerms - termCount) >= 2)) {
            if (l > 0) {
                double fractionalSeconds = l / (1000.0D);
                DecimalFormat fix3 = new DecimalFormat("0.000");
                sb.append(fix3.format(fractionalSeconds)).append('s');
                termCount++;
                //l = l - ((long)fractionalSeconds * (long)1000);
            }
        } else {
            long seconds = (l / 1000L);
            if (seconds > 0) {
                sb.append(seconds).append('s');
                termCount++;
                //l = l - ((long)seconds * (long)1000);
            }
        }
        //
        return sb.toString();
    }
    
    public static String formatTime(long timeInterval) {
        return formatTime(timeInterval, 2, false);
    }
    
    public static String formatTime(long timeInterval, int maxTerms) {
        return formatTime(timeInterval, maxTerms, false);
    }

	/**
	 * Helper to format time HTTP conform
	 * @param time
	 * @return
	 */
	public static String makeHTTPDate(long time) {
		// For HTTP, GMT == UTC
		SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'",Locale.US);
		sdf.setTimeZone(TZ_UTC);
		return sdf.format(new Date(time));
	}
	
// FIXME: For me it returns a parsed time with 2 hours difference, so it seems to parse localtime. WHY?
	
//	public static Date parseHTTPDate(String date) throws ParseException {
//		SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'",Locale.US);
//		sdf.setTimeZone(TZ_UTC);
//		return sdf.parse(date);
//	}

	
	/**
	 * @return Returns the passed date with the same year/month/day but with the time set to 00:00:00.000
	 */
	public static Date setTimeToZero(final Date date) {
		// We need to cut off the hour/minutes/seconds
		final GregorianCalendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
		calendar.setTimeInMillis(date.getTime()); // We must not use setTime(date) in case the date is not UTC.
		calendar.set(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH), 0, 0, 0);
		calendar.set(Calendar.MILLISECOND, 0);
		return calendar.getTime();
	}
}
