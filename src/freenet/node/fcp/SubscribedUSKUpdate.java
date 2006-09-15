/*
  SubsribeUSKUpdate.java / Freenet
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

import freenet.keys.USK;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class SubscribedUSKUpdate extends FCPMessage {

	final String identifier;
	final long edition;
	final USK key;
	
	static final String name = "SubscribedUSKUpdate";
	
	public SubscribedUSKUpdate(String identifier, long l, USK key) {
		this.identifier = identifier;
		this.edition = l;
		this.key = key;
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet();
		fs.put("Identifier", identifier);
		fs.put("Edition", Long.toString(edition));
		fs.put("URI", key.getURI().toString());
		return fs;
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "SubscribedUSKUpdate goes from server to client not the other way around", identifier);
	}

}
