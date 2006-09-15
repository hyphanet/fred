/*
  Peer.java / Freenet
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
import freenet.support.SimpleFieldSet;

public class Peer extends FCPMessage {
	static final String name = "Peer";
	
	final PeerNode pn;
	final boolean withMetadata;
	final boolean withVolatile;
	
	public Peer(PeerNode pn, boolean withMetadata, boolean withVolatile) {
		this.pn = pn;
		this.withMetadata = withMetadata;
		this.withVolatile = withVolatile;
	}
	
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = pn.exportFieldSet();
		if(withMetadata) {
			SimpleFieldSet meta = pn.exportMetadataFieldSet();
			if(!meta.isEmpty()) {
			 	fs.put("metadata", meta);
			}
		}
		if(withVolatile) {
			SimpleFieldSet vol = pn.exportVolatileFieldSet();
			if(!vol.isEmpty()) {
			 	fs.put("volatile", vol);
			}
		}
		return fs;
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "Peer goes from server to client not the other way around", null);
	}

}
