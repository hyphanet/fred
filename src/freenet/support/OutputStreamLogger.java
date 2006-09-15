/*
  OutputStreamLogger.java / Freenet
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

import java.io.OutputStream;

public class OutputStreamLogger extends OutputStream {

	final int prio;
	final String prefix;
	
	public OutputStreamLogger(int prio, String prefix) {
		this.prio = prio;
		this.prefix = prefix;
	}

	public void write(int b) {
		Logger.logStatic(this, prefix+(char)b, prio);
	}
	
	public void write(byte[] buf, int offset, int length) {
		Logger.logStatic(this, prefix+new String(buf, offset, length), prio);
	}
	
	public void write(byte[] buf) {
		write(buf, 0, buf.length);
	}
}
