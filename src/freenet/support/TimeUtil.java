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

/**
 * Time formatting utility.
 * Formats milliseconds into a week/day/hour/second string (without milliseconds).
 */
public class TimeUtil {
	public static String formatTime(long timeInterval, int maxTerms) {
		StringBuffer sb = new StringBuffer(64);
		long l = timeInterval / 1000;  // ms -> s
		int termCount = 0;
		//
		int weeks = (int)(l / (7*24*60*60));
		if (weeks > 0) {
		  sb.append(weeks + "w");
		  termCount++;
		  l = l - (weeks * (7*24*60*60));
		}
		//
		int days = (int)(l / (24*60*60));
		if (days > 0) {
		  sb.append(days + "d");
		  termCount++;
		  l = l - (days * (24*60*60));
		}
		if(termCount >= maxTerms) {
		  return sb.toString();
		}
		//
		int hours = (int)(l / (60*60));
		if (hours > 0) {
		  sb.append(hours + "h");
		  termCount++;
		  l = l - (hours * (60*60));
		}
		if(termCount >= maxTerms) {
		  return sb.toString();
		}
		//
		int minutes = (int)(l / 60);
		if (minutes > 0) {
		  sb.append(minutes + "m");
		  termCount++;
		  l = l - (minutes * 60);
		}
		if(termCount >= maxTerms) {
		  return sb.toString();
		}
		//
		sb.append(l + "s");
		return sb.toString();
	}
	
	public static String formatTime(long timeInterval) {
		return formatTime(timeInterval, 2);
	}
}
