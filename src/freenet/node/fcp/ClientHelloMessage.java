/*
  ClientHelloMessage.java / Freenet
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
import freenet.support.SimpleFieldSet;

/**
 *  ClientHello
 *  Name=Toad's Test Client
 *  ExpectedVersion=0.7.0
 *  End
 */
public class ClientHelloMessage extends FCPMessage {

	public final static String name = "ClientHello";
	String clientName;
	String clientExpectedVersion;
	
	public ClientHelloMessage(SimpleFieldSet fs) throws MessageInvalidException {
		clientName = fs.get("Name");
		clientExpectedVersion = fs.get("ExpectedVersion");
		if(clientName == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "ClientHello must contain a Name field", null);
		if(clientExpectedVersion == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "ClientHello must contain a ExpectedVersion field", null);
		// FIXME check the expected version
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet sfs = new SimpleFieldSet();
		sfs.put("Name", clientName);
		sfs.put("ExpectedVersion", clientExpectedVersion);
		return sfs;
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node) {
		// We know the Hello is valid.
		FCPMessage msg = new NodeHelloMessage(node);
		handler.outputHandler.queue(msg);
		handler.setClientName(clientName);
	}

}
