/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoField;
import java.util.Date;


/**
 * A helper class, which returns the current time and date in UTC timezone.
 */
public class CurrentTimeUTC {

    public static Date get() {
        return Date.from(nowUtc().toInstant());
    }

    /**
     * Get the current time in milliseconds.
     *
     * In the current implementation, this just returns {@link System#currentTimeMillis()}.
     * You should however use CurrentTimeUTC#getInMillis() instead because
     * the JavaDoc of {@link System#currentTimeMillis()} does not explicitly state what time zone it returns.
     * Therefore, by using this wrapper function, your code clearly states that it uses UTC time.
     */
    public static long getInMillis() {
        return System.currentTimeMillis();
    }

    public static int getYear() {
        return nowUtc().get(ChronoField.YEAR);
    }

    public static int getMonth() {
        // Previously, this method used java.util.GregorianCalendar and Calendar.MONTH, which is zero-based.
        // Newer java.time API returns months values starting with one,
        // and subtraction is required to preserve backward compatibility.
        return nowUtc().get(ChronoField.MONTH_OF_YEAR) - 1;
    }

    public static int getDayOfMonth() {
        return nowUtc().get(ChronoField.DAY_OF_MONTH);
    }

    private static OffsetDateTime nowUtc() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
