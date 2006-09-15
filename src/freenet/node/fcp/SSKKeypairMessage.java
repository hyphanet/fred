/*
  SSKKeypairMessage.java / Freenet
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

import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class SSKKeypairMessage extends FCPMessage {

	private final FreenetURI insertURI;
	private final FreenetURI requestURI;
	private final String identifier;
	
	public SSKKeypairMessage(FreenetURI insertURI, FreenetURI requestURI, String identifier) {
		this.insertURI = insertURI;
		this.requestURI = requestURI;
		this.identifier = identifier;
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet sfs = new SimpleFieldSet();
		sfs.put("InsertURI", insertURI.toString());
		sfs.put("RequestURI", requestURI.toString());
		if(identifier != null) // is optional on these two only
			sfs.put("Identifier", identifier);
		return sfs;
	}

	public String getName() {
		return "SSKKeypair";
	}

	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "SSKKeypair goes from server to client not the other way around", identifier);
	}
	
	

}
