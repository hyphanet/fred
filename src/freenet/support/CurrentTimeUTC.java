/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;


/**
 * A helper class, which returns the current time and date in UTC timezone.
 */
public class CurrentTimeUTC {

    public static Date get() {
        return new Date();
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
        return LocalDate.now(ZoneOffset.UTC).getYear();
    }

    public static int getMonth() {
        // Previously, this method used java.util.GregorianCalendar and Calendar.MONTH, which is zero-based.
        // Newer java.time API returns months values starting from one,
        // and subtraction is required to preserve backward compatibility.
        return LocalDate.now(ZoneOffset.UTC).getMonthValue() - 1;
    }

    public static int getDayOfMonth() {
        return LocalDate.now(ZoneOffset.UTC).getDayOfMonth();
    }
}
