/*
  ShutdownMessage.java / Freenet
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
import freenet.node.fcp.FCPMessage;
import freenet.support.SimpleFieldSet;

public class ShutdownMessage extends FCPMessage{
	public final static String name = "Shutdown";
	
	public ShutdownMessage() throws MessageInvalidException {
	}

	public SimpleFieldSet getFieldSet() {
		return new SimpleFieldSet();
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node) {
		FCPMessage msg = new ProtocolErrorMessage(ProtocolErrorMessage.SHUTTING_DOWN,true,"The node is shutting down","Node");
		handler.outputHandler.queue(msg);
		node.exit("Received FCP shutdown message");
	}	
}