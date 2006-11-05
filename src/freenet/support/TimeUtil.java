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

/**
 * Time formatting utility.
 * Formats milliseconds into a week/day/hour/second/milliseconds string.
 */
public class TimeUtil {
	public static String formatTime(long timeInterval, int maxTerms, boolean withSecondFractions) {
		StringBuffer sb = new StringBuffer(64);
		long l = timeInterval;
		int termCount = 0;
		//
		int weeks = (int)(l / ((long)7*24*60*60*1000));
		if (weeks > 0) {
		  sb.append(weeks + "w");
		  termCount++;
		  l = l - ((long)weeks * ((long)7*24*60*60*1000));
		}
		//
		int days = (int)(l / ((long)24*60*60*1000));
		if (days > 0) {
		  sb.append(days + "d");
		  termCount++;
		  l = l - ((long)days * ((long)24*60*60*1000));
		}
		if(termCount >= maxTerms) {
		  return sb.toString();
		}
		//
		int hours = (int)(l / ((long)60*60*1000));
		if (hours > 0) {
		  sb.append(hours + "h");
		  termCount++;
		  l = l - ((long)hours * ((long)60*60*1000));
		}
		if(termCount >= maxTerms) {
		  return sb.toString();
		}
		//
		int minutes = (int)(l / ((long)60*1000));
		if (minutes > 0) {
		  sb.append(minutes + "m");
		  termCount++;
		  l = l - ((long)minutes * ((long)60*1000));
		}
		if(termCount >= maxTerms) {
		  return sb.toString();
		}
		if(withSecondFractions && ((maxTerms - termCount) >= 2)) {
			if (l > 0) {
				double fractionalSeconds = ((double) l) / ((double) 1000.0);
				DecimalFormat fix4 = new DecimalFormat("0.0000");
				sb.append(fix4.format(fractionalSeconds) + "s");
				termCount++;
				l = l - ((long)fractionalSeconds * (long)1000);
			}
		} else {
			int seconds = (int)(l / (long)1000);
			if (seconds > 0) {
				sb.append(seconds + "s");
				termCount++;
				l = l - ((long)seconds * (long)1000);
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
}
