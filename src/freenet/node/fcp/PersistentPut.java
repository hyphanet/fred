/*
  PersistentPut.java / Freenet
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

public class PersistentPut extends FCPMessage {

	static final String name = "PersistentPut";
	
	final String identifier;
	final FreenetURI uri;
	final int verbosity; 
	final short priorityClass;
	final short uploadFrom;
	final short persistenceType; 
	final File origFilename;
	final String mimeType;
	final boolean global;
	final FreenetURI targetURI;
	final long size;
	final String token;
	final boolean started;
	final int maxRetries;
	
	public PersistentPut(String identifier, FreenetURI uri, int verbosity, 
			short priorityClass, short uploadFrom, FreenetURI targetURI, 
			short persistenceType, File origFilename, String mimeType, 
			boolean global, long size, String clientToken, boolean started, int maxRetries) {
		this.identifier = identifier;
		this.uri = uri;
		this.verbosity = verbosity;
		this.priorityClass = priorityClass;
		this.uploadFrom = uploadFrom;
		this.targetURI = targetURI;
		this.persistenceType = persistenceType;
		this.origFilename = origFilename;
		this.mimeType = mimeType;
		this.global = global;
		this.size = size;
		this.token = clientToken;
		this.started = started;
		this.maxRetries = maxRetries;
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet();
		fs.put("Identifier", identifier);
		fs.put("URI", uri.toString(false));
		fs.put("Verbosity", verbosity);
		fs.put("PriorityClass", priorityClass);
		fs.put("UploadFrom", ClientPutMessage.uploadFromString(uploadFrom));
		fs.put("Persistence", ClientRequest.persistenceTypeString(persistenceType));
		if(origFilename != null)
			fs.put("Filename", origFilename.getAbsolutePath());
		if(targetURI != null)
			fs.put("TargetURI", targetURI.toString());
		if(mimeType != null)
			fs.put("Metadata.ContentType", mimeType);
		fs.put("Global", global);
		if(size != -1)
			fs.put("DataLength", size);
		if(token != null)
			fs.put("ClientToken", token);
		fs.put("Started", started);
		fs.put("MaxRetries", maxRetries);
		return fs;
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "PersistentPut goes from server to client not the other way around", identifier);
	}

}
