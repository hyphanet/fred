/*
  DiskDirPutFile.java / Freenet
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

import freenet.client.DefaultMIMETypes;
import freenet.support.SimpleFieldSet;
import freenet.support.io.Bucket;
import freenet.support.io.FileBucket;

public class DiskDirPutFile extends DirPutFile {

	final File file;
	
	public DiskDirPutFile(SimpleFieldSet subset, String identifier) throws MessageInvalidException {
		super(subset, identifier);
		String s = subset.get("Filename");
		if(s == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "Missing field: Filename on "+name, identifier);
		file = new File(s);
	}

	protected String guessMIME() {
		String mime = super.guessMIME();
		if(mime == null) {
			mime = DefaultMIMETypes.guessMIMEType(file.getName(), false /* fixme? */);
		}
		return mime;
	}

	public Bucket getData() {
		return new FileBucket(file, true, false, false, false);
	}

}
