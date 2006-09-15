/*
  SubscribeUSKMessage.java / Freenet
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

import java.net.MalformedURLException;

import freenet.keys.FreenetURI;
import freenet.keys.USK;
import freenet.node.Node;
import freenet.support.Fields;
import freenet.support.SimpleFieldSet;

/**
 * Sent by a client to subscribe to a USK. The client will then be notified whenever a new latest version of
 * the USK is available. There is a flag for whether the node should actively probe for the USK.
 * 
 * SubscribeUSK
 * URI=USK@60I8H8HinpgZSOuTSD66AVlIFAy-xsppFr0YCzCar7c,NzdivUGCGOdlgngOGRbbKDNfSCnjI0FXjHLzJM4xkJ4,AQABAAE/index/4
 * DontPoll=true // meaning passively subscribe, don't cause the node to actively probe for it
 * Identifier=identifier
 * End
 */
public class SubscribeUSKMessage extends FCPMessage {

	public static final String name = "SubscribeUSK";

	final USK key;
	final boolean dontPoll;
	final String identifier;
	
	public SubscribeUSKMessage(SimpleFieldSet fs) throws MessageInvalidException {
		this.identifier = fs.get("Identifier");
		if(identifier == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No Identifier!", null);
		String suri = fs.get("URI");
		if(suri == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "Expected a URI on SubscribeUSK", identifier);
		FreenetURI uri;
		try {
			uri = new FreenetURI(suri);
			key = USK.create(uri);
		} catch (MalformedURLException e) {
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Could not parse URI: "+e, identifier);
		}
		this.dontPoll = Fields.stringToBool(fs.get("DontPoll"), false);
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet();
		fs.put("URI", key.getURI().toString());
		fs.put("DontPoll", Boolean.toString(dontPoll));
		return fs;
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		new SubscribeUSK(this, node.clientCore, handler);
	}

}
