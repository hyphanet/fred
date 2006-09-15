/*
  RedirectDirPutFile.java / Freenet
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

import freenet.client.ClientMetadata;
import freenet.client.async.ManifestElement;
import freenet.keys.FreenetURI;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.io.Bucket;

public class RedirectDirPutFile extends DirPutFile {

	final FreenetURI targetURI;
	
	public RedirectDirPutFile(SimpleFieldSet subset, String identifier) throws MessageInvalidException {
		super(subset, identifier);
		String target = subset.get("TargetURI");
		if(target == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "TargetURI missing but UploadFrom=redirect", identifier);
		try {
			targetURI = new FreenetURI(target);
		} catch (MalformedURLException e) {
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Invalid TargetURI: "+e, identifier);
		}
        if(Logger.shouldLog(Logger.MINOR, this))
        	Logger.minor(this, "targetURI = "+targetURI);
		super.meta = new ClientMetadata();
	}

	public Bucket getData() {
		return null;
	}

	public ManifestElement getElement() {
		return new ManifestElement(name, targetURI, getMIMEType());
	}
}
