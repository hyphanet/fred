/*
  DirectDirPutFile.java / Freenet
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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import freenet.support.SimpleFieldSet;
import freenet.support.io.Bucket;
import freenet.support.io.BucketFactory;
import freenet.support.io.BucketTools;

/**
 * Specialized DirPutFile for direct uploads.
 */
public class DirectDirPutFile extends DirPutFile {

	private final Bucket data;
	private final long length;
	
	public DirectDirPutFile(SimpleFieldSet subset, String identifier, BucketFactory bf) throws MessageInvalidException {
		super(subset, identifier);
		String s = subset.get("DataLength");
		if(s == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "UploadFrom=direct requires a DataLength for "+name, identifier);
		try {
			length = Long.parseLong(s);
		} catch (NumberFormatException e) {
			throw new MessageInvalidException(ProtocolErrorMessage.ERROR_PARSING_NUMBER, "Could not parse DataLength: "+e.toString(), identifier);
		}
		try {
			data = bf.makeBucket(length);
		} catch (IOException e) {
			throw new MessageInvalidException(ProtocolErrorMessage.INTERNAL_ERROR, "Internal error: could not allocate temp bucket: "+e.toString(), identifier);
		}
	}

	public long bytesToRead() {
		return length;
	}

	public void read(InputStream is) throws IOException {
		BucketTools.copyFrom(data, is, length);
	}

	public void write(OutputStream os) throws IOException {
		BucketTools.copyTo(data, os, length);
	}

	public Bucket getData() {
		return data;
	}

}
