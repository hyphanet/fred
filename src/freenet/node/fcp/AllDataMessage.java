/*
  AllDataMessage.java / Freenet
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
import freenet.support.io.Bucket;

/**
 * All the data, all in one big chunk. Obviously we must already have
 * all the data to send it. We do not want to have to block on a request,
 * especially as there may be errors.
 */
public class AllDataMessage extends DataCarryingMessage {

	final long dataLength;
	final String identifier;
	
	public AllDataMessage(Bucket bucket, String identifier) {
		this.bucket = bucket;
		this.dataLength = bucket.size();
		this.identifier = identifier;
	}

	long dataLength() {
		return dataLength;
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet();
		fs.put("DataLength", Long.toString(dataLength));
		fs.put("Identifier", identifier);
		return fs;
	}

	public String getName() {
		return "AllData";
	}

	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "AllData goes from server to client not the other way around", identifier);
	}

	String getIdentifier() {
		return identifier;
	}

}
