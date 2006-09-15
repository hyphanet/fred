/*
  FinishedCompressionMessage.java / Freenet
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

import freenet.client.events.FinishedCompressionEvent;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class FinishedCompressionMessage extends FCPMessage {

	final String identifier;
	final int codec;
	final long origSize;
	final long compressedSize;
	
	public FinishedCompressionMessage(String identifier, FinishedCompressionEvent event) {
		this.identifier = identifier;
		this.codec = event.codec;
		this.compressedSize = event.compressedSize;
		this.origSize = event.originalSize;
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet();
		fs.put("Identifier", identifier);
		fs.put("Codec", Integer.toString(codec));
		fs.put("OriginalSize", Long.toString(origSize));
		fs.put("CompressedSize", Long.toString(compressedSize));
		return fs;
	}

	public String getName() {
		return "FinishedCompression";
	}

	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "FinishedCompression goes from server to client not the other way around", identifier);
	}

}
