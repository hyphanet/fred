/*
  SizeUtil.java / Freenet
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
 * Size formatting utility.
 */
public class SizeUtil {

	public static String formatSize(long sz) {
		// First determine suffix
		
		String[] suffixes = {"B", "KiB","MiB","GiB","TiB","PiB","EiB","ZiB","YiB"};
		long s = 1;
		int i;
		for(i=0;i<suffixes.length;i++) {
			s *= 1024;
			if(s > sz) {
				break;
				// Smaller than multiplier [i] - use the previous one
			}
		}
		
		s /= 1024; // we use the previous unit
		if (s == 1)  // Bytes? Then we don't need real numbers with a comma
		{
			return sz + " " + suffixes[0];
		}
		else
		{
			double mantissa = (double)sz / (double)s;
			String o = Double.toString(mantissa);
			if(o.indexOf('.') == 3)
				o = o.substring(0, 3);
			else if((o.indexOf('.') > -1) && (o.indexOf('E') == -1) && (o.length() > 4))
				o = o.substring(0, 4);
			o += " " + suffixes[i];
			return o;
		}
	}
}
