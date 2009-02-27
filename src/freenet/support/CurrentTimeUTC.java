/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;


/**
 * A wrapper class around a GregorianCalendar which always returns the current time.
 * This is useful for working around the pitfall of class Calendar: It only returns the current time when you first use a get*() function,
 * in any get*() calls after the first call, the time value of the first call is returned. One would have to call Calendar.clear() before each
 * get to obtain the current time and this class takes care of that for you.
 * 
 * Further, this class is synchronized so you do not need to worry about synchronization of a Calendar anymore.
 */
public class CurrentTimeUTC {

	private static final GregorianCalendar mCalendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"));

	public static Date get() {
		return new Date();
	}

	public static long getInMillis() {
		return System.currentTimeMillis();
	}

	public static int getYear() {
		synchronized(mCalendar) {
			mCalendar.setTimeInMillis(System.currentTimeMillis());
			return mCalendar.get(Calendar.YEAR);
		}
	}

	public static int getMonth() {
		synchronized(mCalendar) {
			mCalendar.setTimeInMillis(System.currentTimeMillis());
			return mCalendar.get(Calendar.MONTH);
		}
	}

	public static int getDayOfMonth() {
		synchronized(mCalendar) {
			mCalendar.setTimeInMillis(System.currentTimeMillis());
			return mCalendar.get(Calendar.DAY_OF_MONTH);
		}
	}

}
