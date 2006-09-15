/*
  ManifestElement.java / Freenet
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

package freenet.client.async;

import freenet.keys.FreenetURI;
import freenet.support.io.Bucket;

/**
 * Represents an element in a manifest. Fed to SimpleManifestPutter.
 */
public class ManifestElement {

	/** Filename */
	final String name;
	
	final String fullName;
	
	/** Data to be inserted. Can be null, if the insert has completed. */
	final Bucket data;
	
	/** MIME type override. null => use default for filename */
	final String mimeOverride;
	
	/** Original size of the bucket. Can be set explicitly even if data == null. */
	final long dataSize;
	
	/** Redirect target */
	final FreenetURI targetURI;
	
	public ManifestElement(String name, String fullName, Bucket data, String mimeOverride, long size) {
		this.name = name;
		this.fullName = fullName;
		this.data = data;
		this.mimeOverride = mimeOverride;
		this.dataSize = size;
		this.targetURI = null;
	}
	
	public ManifestElement(String name, Bucket data, String mimeOverride, long size) {
		this.name = name;
		this.fullName = name;
		this.data = data;
		this.mimeOverride = mimeOverride;
		this.dataSize = size;
		this.targetURI = null;
	}
	
	public ManifestElement(ManifestElement me, String newName) {
		this.name = newName;
		this.fullName = me.fullName;
		this.data = me.data;
		this.mimeOverride = me.mimeOverride;
		this.dataSize = me.dataSize;
		this.targetURI = me.targetURI;
	}

	public ManifestElement(String name, FreenetURI targetURI, String mimeOverride) {
		this.name = name;
		this.fullName = name;
		this.data = null;
		this.mimeOverride = mimeOverride;
		this.dataSize = -1;
		this.targetURI = targetURI;
	}
	
	public int hashCode() {
		return name.hashCode();
	}
	
	public boolean equals(Object o) {
		if(this == o) return true;
		if(o instanceof ManifestElement) {
			if(((ManifestElement)o).name.equals(name)) return true;
		}
		return false;
	}

	public void freeData() {
		if(data != null)
			data.free();
	}

	public String getName() {
		return name;
	}

	public String getMimeTypeOverride() {
		return mimeOverride;
	}

	public Bucket getData() {
		return data;
	}
	
	public long getSize() {
		return dataSize;
	}

	public FreenetURI getTargetURI() {
		return targetURI;
	}
}
