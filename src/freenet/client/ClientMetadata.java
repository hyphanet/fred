/*
  ClientMetadata.java / Freenet
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

package freenet.client;

/**
 * Stores the metadata that the client might actually be interested in.
 */
public class ClientMetadata implements Cloneable {
	
	/** The document MIME type */
	private String mimeType;

	public ClientMetadata(String mime) {
		mimeType = mime;
	}

	/** Create an empty ClientMetadata instance */
	public ClientMetadata() {
		mimeType = null;
	}
	
	/** Get the document MIME type. Will always be a valid MIME type, unless there
	 * has been an error; if it is unknown, will return application/octet-stream. */
	public String getMIMEType() {
		if((mimeType == null) || (mimeType.length() == 0))
			return DefaultMIMETypes.DEFAULT_MIME_TYPE;
		return mimeType;
	}

	/**
	 * Merge the given ClientMetadata, without overwriting our
	 * existing information.
	 */
	public void mergeNoOverwrite(ClientMetadata clientMetadata) {
		if((mimeType == null) || mimeType.equals(""))
			mimeType = clientMetadata.mimeType;
	}

	public boolean isTrivial() {
		return ((mimeType == null) || mimeType.equals(""));
	}
	
	public Object clone() {
		try {
			return super.clone();
		} catch (CloneNotSupportedException e) {
			throw new Error(e);
		}
	}
}
