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
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Time formatting utility.
 * Formats milliseconds into a week/day/hour/second/milliseconds string.
 */
public class TimeUtil {

	public static final TimeZone TZ_UTC = TimeZone.getTimeZone("UTC");

    // https://www.ietf.org/rfc/rfc6265.html#section-5.1.1
    private static final Pattern DATE_DELIMETERS = Pattern.compile("[\\x09\\x20-\\x2F\\x3B-\\x40\\x5B-\\x60\\x7B-\\x7E]+");
    private static final Pattern TIME_PATTERN = Pattern.compile("(\\d{1,2}):(\\d{1,2}):(\\d{1,2})");
    private static final Pattern DAY_OF_MONTH_PATTERN = Pattern.compile("\\d{1,2}");
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\d{2,4}");

    private static final List<DateTimeFormatter> DATE_TIME_FORMATTERS = Collections.unmodifiableList(
        Arrays.asList(
            DateTimeFormatter.RFC_1123_DATE_TIME,
            // Same as RFC1123 but with timezone name
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz"),

            // Monday, 07 Nov 1994 08:49:37 GMT
            DateTimeFormatter.ofPattern("EEEE, dd MMM yyyy HH:mm:ss zzz"),

            // Sunday, 06-Nov-94 08:49:37 GMT ; RFC 850, obsoleted by RFC 1036
            // Current century two digit years
            DateTimeFormatter.ofPattern("EEEE, dd'-'MMM'-'uu HH:mm:ss zzz"),

            // two digit years between 1900 - 2000
            new DateTimeFormatterBuilder()
                .appendPattern("EEEE, dd'-'MMM'-'")
                .appendValueReduced(ChronoField.YEAR, 2, 2, 1900)
                .appendPattern(" HH:mm:ss zzz")
                .toFormatter(),

            DateTimeFormatter.ISO_DATE_TIME,
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ISO_ZONED_DATE_TIME,
            DateTimeFormatter.ISO_OFFSET_DATE,
            DateTimeFormatter.ISO_INSTANT,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:sszzz")

        )
    );

    private static final List<String> MONTHS = Collections.unmodifiableList(
        Arrays.asList("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    );

    private static final List<DateTimeFormatter> MONTH_FORMATTERS = Collections.unmodifiableList(
        Arrays.asList(
            DateTimeFormatter.ofPattern("MMMM", Locale.US),
            DateTimeFormatter.ofPattern("MMMM", Locale.getDefault())
        )
    );

    private static final List<DateTimeFormatter> DAY_OF_WEEK_FORMATTER = Collections.unmodifiableList(
        Arrays.asList(
            DateTimeFormatter.ofPattern("EEE", Locale.US),
            DateTimeFormatter.ofPattern("EEE", Locale.getDefault()),
            DateTimeFormatter.ofPattern("EEEE", Locale.US),
            DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())
        )
    );

    private static final List<DateTimeFormatter> TZ_FORMATTERS = Collections.unmodifiableList(
        Arrays.asList(
            new DateTimeFormatterBuilder().appendOffset("+HH:mm", "Z").toFormatter(),
            new DateTimeFormatterBuilder().appendOffset("+HHmm", "Z").toFormatter(),
            new DateTimeFormatterBuilder().appendOffset("+HH:MM:ss", "Z").toFormatter(),
            new DateTimeFormatterBuilder().appendOffset("+HHMMss", "Z").toFormatter(),
            new DateTimeFormatterBuilder().appendZoneOrOffsetId().toFormatter()
        )
    );


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

    /**
     * Parses text datetime defined in multiple standards (RFC822, RFC1036, RFC1123, ISO8601)
     *
     * @param dateTimeText datetime text, must have textual month or be an ISO8601 timestamp.
     * @return date time with time zone
     * @throws DateTimeParseException if cannot parse datetime
     */
    public static ZonedDateTime parseHttpDateTime(String dateTimeText) {
        for (DateTimeFormatter df : DATE_TIME_FORMATTERS) {
            try {
                return ZonedDateTime.parse(dateTimeText, df);
            } catch (DateTimeParseException ignored) {
                // continue with next pattern
            }
            try {
                return LocalDateTime.parse(dateTimeText, df).atZone(ZoneOffset.UTC);
            } catch (DateTimeParseException ignored) {
                // continue with next pattern
            }
        }

        boolean foundTime = false;
        boolean foundDayOfWeek = false;
        boolean foundDayOfMonth = false;
        boolean foundMonth = false;
        boolean foundYear = false;

        int hour = -1;
        int min = -1;
        int second = -1;

        ZoneId zoneId = ZoneOffset.UTC;

        int dayOfWeek = -1;
        int dayOfMonth = -1;
        int month = -1;
        int year = -1;

        // This algorithm is defined in https://www.ietf.org/rfc/rfc6265.html#section-5.1.1
        // It is extended with timezone parsing

        String[] tokens = DATE_DELIMETERS.split(dateTimeText);
        for (String token : tokens) {
            Matcher timeMatcher = TIME_PATTERN.matcher(token);
            if (!foundTime && timeMatcher.matches()) {
                hour = Integer.parseInt(timeMatcher.group(1));
                min = Integer.parseInt(timeMatcher.group(2));
                second = Integer.parseInt(timeMatcher.group(3));
                foundTime = true;
                continue;
            }
            if (!foundDayOfMonth && DAY_OF_MONTH_PATTERN.matcher(token).matches()) {
                dayOfMonth = Integer.parseInt(token);
                foundDayOfMonth = true;
                continue;
            }
            if (!foundMonth) {
                month = parseMonth(token);
                if (month > 0) {
                    foundMonth = true;
                    continue;
                }
            }
            if (!foundYear && YEAR_PATTERN.matcher(token).matches()) {
                year = Integer.parseInt(token);
                foundYear = true;
            }

            if (!foundDayOfWeek) {
                dayOfWeek = parseDayOfWeek(token);
                if (dayOfWeek > 0) {
                    foundDayOfWeek = true;
                }
            }

            ZoneId newZoneId = parseZoneId(dateTimeText, foundTime, foundYear, token);
            if (newZoneId != null) {
                zoneId = newZoneId;
            }
        }

        if (foundTime && foundDayOfMonth && foundMonth && foundYear) {
            if (year >= 70 && year <= 99) {
                year += 1900;
            }
            if (year >= 0 && year <= 69) {
                year += 2000;
            }
            ZonedDateTime dateTime;
            try {
                dateTime = ZonedDateTime.of(
                    year,
                    month,
                    dayOfMonth,
                    hour,
                    min,
                    second,
                    0,
                    zoneId
                );
            } catch (DateTimeException dte) {
                throw new DateTimeParseException("Cannot parse datetime", dateTimeText, 0, dte);
            }
            if (foundDayOfWeek && dayOfWeek != dateTime.getDayOfWeek().getValue()) {
                throw new DateTimeParseException("Invalid day of week", dateTimeText, -0);
            }
            return dateTime;
        }

        throw new DateTimeParseException("Cannot parse datetime", dateTimeText, 0);
    }

    private static int parseMonth(String token) {
        int month = MONTHS.indexOf(token.toLowerCase(Locale.ROOT)) + 1;
        if (month > 0) {
            return month;
        }
        for (DateTimeFormatter monthFormatter : MONTH_FORMATTERS) {
            try {
                return monthFormatter.parse(token).get(ChronoField.MONTH_OF_YEAR);
            } catch (DateTimeParseException ignored) {
            }
        }
        return -1;
    }

    private static int parseDayOfWeek(String token) {
        for (DateTimeFormatter dateTimeFormatter : DAY_OF_WEEK_FORMATTER) {
            try {
                return dateTimeFormatter.parse(token).get(ChronoField.DAY_OF_WEEK);
            } catch (DateTimeParseException ignored) {
            }
        }
        return -1;
    }

    private static ZoneId parseZoneId(String dateTimeText, boolean foundTime, boolean foundYear, String token) {
        for (DateTimeFormatter tzFormatter : TZ_FORMATTERS) {
            try {
                return ZoneId.from(tzFormatter.parse(token));
            } catch (DateTimeException ignored) {
            }
        }
        try {
            return ZoneId.of(token);
        } catch (DateTimeException ignored2) {
        }
        try {
            return ZoneId.of(token, ZoneId.SHORT_IDS);
        } catch (DateTimeException ignored3) {
        }
        if (foundYear && foundTime && token.matches("(\\d{6})|(\\d{4})|(\\d{2}:\\d{2}(:\\d{2})?)")) {
            int idx = dateTimeText.indexOf(token);
            if (idx > 0) {
                char c = dateTimeText.charAt(idx - 1);
                if (c == '+' || c == '-') {
                    String offset = c + token;
                    for (DateTimeFormatter tzFormatter : TZ_FORMATTERS) {
                        try {
                            return ZoneId.from(tzFormatter.parse(offset));
                        } catch (DateTimeException ignored) {
                        }
                    }
                }
            }
        }
        return null;
    }


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
