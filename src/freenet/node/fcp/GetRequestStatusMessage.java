/*
  GetRequestStatusMessage.java / Freenet
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

public class GetRequestStatusMessage extends FCPMessage {

	final String identifier;
	final boolean global;
	final boolean onlyData;
	final static String name = "GetRequestStatus";
	
	public GetRequestStatusMessage(SimpleFieldSet fs) {
		this.identifier = fs.get("Identifier");
		this.global = Fields.stringToBool(fs.get("Global"), false);
		this.onlyData = Fields.stringToBool(fs.get("OnlyData"), false);
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet();
		fs.put("Identifier", identifier);
		return fs;
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		ClientRequest req;
		if(global)
			req = handler.server.globalClient.getRequest(identifier);
		else
			req = handler.getClient().getRequest(identifier);
		if(req == null) {
			ProtocolErrorMessage msg = new ProtocolErrorMessage(ProtocolErrorMessage.NO_SUCH_IDENTIFIER, false, null, identifier);
			handler.outputHandler.queue(msg);
		} else {
			req.sendPendingMessages(handler.outputHandler, true, true, onlyData);
		}
	}

}
