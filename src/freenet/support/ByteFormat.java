/*
  ByteFormat.java / Freenet
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

import java.text.NumberFormat;

/**
 * Utility class with one function...
 * Refactored from DefaultInfolet's impl.
 */
public class ByteFormat {
	public static String format(long bytes, boolean html) {
		NumberFormat nf = NumberFormat.getInstance();
		String out;
		if (bytes == 0)
			out = "None";
		else if (bytes > (2L << 32))
		    out = nf.format(bytes >> 30) + " GiB";
		else if (bytes > (2 << 22))
		    out = nf.format(bytes >> 20) + " MiB";
		else if (bytes > (2 << 12))
		    out = nf.format(bytes >> 10) + " KiB";
		else
		    out = nf.format(bytes) + " Bytes";
		if(html)
		    out = out.replaceAll(" ", "&nbsp;");
		return out;
	}
}
