/*
  ListPeersMessage.java / Freenet
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
import freenet.node.PeerNode;
import freenet.support.Fields;
import freenet.support.SimpleFieldSet;

public class ListPeersMessage extends FCPMessage {

	final boolean withMetadata;
	final boolean withVolatile;
	static final String name = "ListPeers";
	
	public ListPeersMessage(SimpleFieldSet fs) {
		withMetadata = Fields.stringToBool(fs.get("WithMetadata"), false);
		withVolatile = Fields.stringToBool(fs.get("WithVolatile"), false);
	}
	
	public SimpleFieldSet getFieldSet() {
		return new SimpleFieldSet();
	}
	
	public String getName() {
		return name;
	}
	
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		PeerNode[] nodes = node.getPeerNodes();
		for(int i = 0; i < nodes.length; i++) {
			PeerNode pn = nodes[i];
			handler.outputHandler.queue(new Peer(pn, withMetadata, withVolatile));
		}
		handler.outputHandler.queue(new EndListPeersMessage());
	}
	
}
