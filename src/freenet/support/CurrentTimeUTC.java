/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;


/**
 * A helper class, which returns the current time and date in UTC timezone.
 *
 * @deprecated use the Java 8 Date-Time APIs instead
 * @see java.time.Instant
 * @see java.time.LocalDate
 * @see java.time.ZoneOffset#UTC
 */
@Deprecated
public class CurrentTimeUTC {

    /**
     * Get the current date.
     * @deprecated use {@link Date#Date() new Date()} or {@link java.time.Instant}
     */
    @Deprecated
    public static Date get() {
        return new Date();
    }

    /**
     * Get the current time in milliseconds since epoch.
     * @return the difference, measured in milliseconds, between the current time and midnight, January 1, 1970 UTC.
     * @deprecated use {@link System#currentTimeMillis()}
     */
    @Deprecated
    public static long getInMillis() {
        return System.currentTimeMillis();
    }

    /**
	 * Get the current calendar year.
	 * @deprecated use {@link LocalDate#getYear() LocalDate.now(ZoneOffset.UTC).getYear()}
	 */
	@Deprecated
	public static int getYear() {
		return LocalDate.now(ZoneOffset.UTC).getYear();
    }

    /**
	 * Get the zero-indexed current calendar month.
	 * @return the zero-indexed month number, where 0 indicates January and 11 indicates December
	 * @deprecated use {@link LocalDate#getMonthValue() LocalDate.now(ZoneOffset.UTC).getMonthValue() - 1}
	 */
	@Deprecated
	public static int getMonth() {
		// Previously, this method used java.util.GregorianCalendar and Calendar.MONTH, which is zero-based.
        // Newer java.time API returns months values starting from one,
        // and subtraction is required to preserve backward compatibility.
        return LocalDate.now(ZoneOffset.UTC).getMonthValue() - 1;
    }

    /**
	 * Get the current day of the month.
	 * @deprecated use {@link LocalDate#getDayOfMonth() LocalDate.now(ZoneOffset.UTC).getDayOfMonth()}
	 */
	@Deprecated
	public static int getDayOfMonth() {
		return LocalDate.now(ZoneOffset.UTC).getDayOfMonth();
    }
}
