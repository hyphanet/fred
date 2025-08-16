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

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Time formatting utility.
 * Formats milliseconds into a week/day/hour/second/milliseconds string.
 */
public class TimeUtil {

	public static final TimeZone TZ_UTC = TimeZone.getTimeZone("UTC");
	private static final Pattern TIME_INTERVAL_PATTERN =
			Pattern.compile("-?(?:(\\d+)w)?(?:(\\d+)d)?(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)([.]\\d+)?s)?");

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
                double fractionalSeconds = l / (1000.0);
                sb.append(String.format(Locale.ROOT, "%.3f", fractionalSeconds)).append('s');
            }
        } else {
            long seconds = SECONDS.convert(l, MILLISECONDS);
            if (seconds > 0) {
                sb.append(seconds).append('s');
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
        Matcher matcher = TIME_INTERVAL_PATTERN.matcher(timeInterval);
        if (!matcher.matches()) {
            throw new NumberFormatException("Unknown format: " + timeInterval);
        }

        String group;
        long millis = 0;
        if ((group = matcher.group(1)) != null) { // weeks
            millis += DAYS.toMillis(7 * Long.parseLong(group));
        }
        if ((group = matcher.group(2)) != null) { // days
            millis += DAYS.toMillis(Long.parseLong(group));
        }
        if ((group = matcher.group(3)) != null) { // hours
            millis += HOURS.toMillis(Long.parseLong(group));
        }
        if ((group = matcher.group(4)) != null) { // minutes
            millis += MINUTES.toMillis(Long.parseLong(group));
        }
        if ((group = matcher.group(5)) != null) { // seconds
            millis += SECONDS.toMillis(Long.parseLong(group));
        }
        if ((group = matcher.group(6)) != null) { // fractional seconds
            millis += (long) (Double.parseDouble(group) * 1000);
        }

        return timeInterval.startsWith("-") ? -millis : millis;
    }

	/**
	 * Helper to format time HTTP conform
	 *
	 * @param time time in milliseconds since epoch
	 * @return RFC 1123 formatted date
	 */
	public static String makeHTTPDate(long time) {
		return DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(time).atOffset(ZoneOffset.UTC));
	}
	
	/**
	 * @return Returns the passed date with the same year/month/day but with the time set to 00:00:00.000
	 */
	public static Date setTimeToZero(final Date date) {
		return Date.from(date.toInstant().truncatedTo(ChronoUnit.DAYS));
	}
}
