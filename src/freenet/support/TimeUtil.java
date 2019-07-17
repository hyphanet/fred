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

import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Time formatting utility.
 * Formats milliseconds into a week/day/hour/second/milliseconds string.
 */
public class TimeUtil {

	public static final TimeZone TZ_UTC = TimeZone.getTimeZone("UTC");

	/**
	 * It converts a given time interval into a 
	 * week/day/hour/second.milliseconds string.
	 * @param timeInterval interval to convert, millis
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
		long weeks = DAYS.convert(l, MILLISECONDS) / 7;
		if (weeks > 0) {
            sb.append(weeks).append('w');
            termCount++;
            l = l - DAYS.toMillis(7 * weeks);
        }
        if(termCount >= maxTerms) {
            return sb.toString();
        }
        //
        long days = DAYS.convert(l, MILLISECONDS);
        if (days > 0) {
            sb.append(days).append('d');
            termCount++;
            l = l - DAYS.toMillis(days);
        }
        if(termCount >= maxTerms) {
          return sb.toString();
        }
        //
        long hours = HOURS.convert(l, MILLISECONDS);
        if (hours > 0) {
            sb.append(hours).append('h');
            termCount++;
            l = l - HOURS.toMillis(hours);
        }
        if(termCount >= maxTerms) {
            return sb.toString();
        }
        //
        long minutes = MINUTES.convert(l, MILLISECONDS);
        if (minutes > 0) {
            sb.append(minutes).append('m');
            termCount++;
            l = l - MINUTES.toMillis(minutes);
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
            long seconds = SECONDS.convert(l, MILLISECONDS);
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

    public static long toMillis(String timeInterval) {
        byte sign = 1;
        if (timeInterval.contains("-")) {
            sign = -1;
            timeInterval = timeInterval.substring(1);
        }

        String[] terms = timeInterval.split("(?<=[a-z])");

        long millis = 0;
        for (String term : terms) {
            if (term.length() == 0) continue;

            char measure = term.charAt(term.length() - 1);
            switch(measure){
                case 'w':
                    millis += 7 * MILLISECONDS.convert(Long.parseLong(term.substring(0, term.length() - 1)), DAYS);
                    break;
                case 'd':
                    millis += MILLISECONDS.convert(Short.parseShort(term.substring(0, term.length() - 1)), DAYS);
                    break;
                case 'h':
                    millis += MILLISECONDS.convert(Short.parseShort(term.substring(0, term.length() - 1)), HOURS);
                    break;
                case 'm':
                    millis += MILLISECONDS.convert(Short.parseShort(term.substring(0, term.length() - 1)), MINUTES);
                    break;
                case 's':
                    if (term.contains(".")) {
                        millis += Integer.parseInt(term.replaceAll("[a-z.]", ""));
                    } else {
                        millis += MILLISECONDS.convert(Short.parseShort(term.substring(0, term.length() - 1)), SECONDS);
                    }
                    break;
                default:
                    throw new NumberFormatException("Unknown format: " + (sign > 0 ? "" : "-") + timeInterval);
            }
        }

        return millis * sign;
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
