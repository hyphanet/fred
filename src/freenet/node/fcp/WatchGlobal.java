/*
  WatchGlobal.java / Freenet
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

package freenet.node.fcp;

import freenet.node.Node;
import freenet.support.Fields;
import freenet.support.SimpleFieldSet;

public class WatchGlobal extends FCPMessage {

	final boolean enabled;
	final int verbosityMask;
	static final String name = "WatchGlobal";

	public WatchGlobal(SimpleFieldSet fs) throws MessageInvalidException {
		enabled = Fields.stringToBool(fs.get("Enabled"), true);
		String s = fs.get("VerbosityMask");
		if(s != null)
			try {
				verbosityMask = Integer.parseInt(s);
			} catch (NumberFormatException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.ERROR_PARSING_NUMBER, e.toString(), null);
			}
		else
			verbosityMask = Integer.MAX_VALUE;
	}
	
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet();
		fs.put("Enabled", Boolean.toString(enabled));
		fs.put("VerbosityMask", Integer.toString(verbosityMask));
		return fs;
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		handler.getClient().setWatchGlobal(enabled, verbosityMask);
	}

}
