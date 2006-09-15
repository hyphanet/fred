/*
  StartedCompressionMessage.java / Freenet
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

public class StartedCompressionMessage extends FCPMessage {

	final String identifier;
	
	final int codec;
	
	public StartedCompressionMessage(String identifier, int codec) {
		this.identifier = identifier;
		this.codec = codec;
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet();
		fs.put("Identifier", identifier);
		fs.put("Codec", Integer.toString(codec));
		return fs;
	}

	public String getName() {
		return "StartedCompression";
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "StartedCompression goes from server to client not the other way around", identifier);
	}

}
