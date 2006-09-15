/*
  PersistentGet.java / Freenet
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

import java.io.File;

import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;

/**
 * Sent by the node to a client when it asks for a list of current requests.
 * PersistentGet
 * End
 */
public class PersistentGet extends FCPMessage {

	static final String name = "PersistentGet";
	
	final String identifier;
	final FreenetURI uri;
	final int verbosity;
	final short priorityClass;
	final short returnType;
	final short persistenceType;
	final File targetFile;
	final File tempFile;
	final String clientToken;
	final boolean global;
	final boolean started;
	final int maxRetries;
	
	public PersistentGet(String identifier, FreenetURI uri, int verbosity, 
			short priorityClass, short returnType, short persistenceType, 
			File targetFile, File tempFile, String clientToken, boolean global, boolean started, int maxRetries) {
		this.identifier = identifier;
		this.uri = uri;
		this.verbosity = verbosity;
		this.priorityClass = priorityClass;
		this.returnType = returnType;
		this.persistenceType = persistenceType;
		this.targetFile = targetFile;
		this.tempFile = tempFile;
		this.clientToken = clientToken;
		this.global = global;
		this.started = started;
		this.maxRetries = maxRetries;
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet();
		fs.put("Identifier", identifier);
		fs.put("URI", uri.toString(false));
		fs.put("Verbosity", verbosity);
		fs.put("ReturnType", ClientGetMessage.returnTypeString(returnType));
		fs.put("PersistenceType", ClientRequest.persistenceTypeString(persistenceType));
		if(returnType == ClientGetMessage.RETURN_TYPE_DISK) {
			fs.put("Filename", targetFile.getAbsolutePath());
			fs.put("TempFilename", tempFile.getAbsolutePath());
		}
		fs.put("PriorityClass", priorityClass);
		if(clientToken != null)
			fs.put("ClientToken", clientToken);
		fs.put("Global", global);
		fs.put("Started", started);
		fs.put("MaxRetries", maxRetries);
		return fs;
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "PersistentGet goes from server to client not the other way around", identifier);
	}

}
