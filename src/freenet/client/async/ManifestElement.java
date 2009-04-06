/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.keys.FreenetURI;
import freenet.support.api.Bucket;

/**
 * Represents an element in a manifest. Fed to SimpleManifestPutter.
 */
public class ManifestElement {

	/** Filename */
	final String name;
	
	final String fullName;
	
	/** Data to be inserted. Can be null, if the insert has completed. */
	Bucket data;
	
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
	
	@Override
	public int hashCode() {
		return name.hashCode();
	}
	
	@Override
	public boolean equals(Object o) {
		if(this == o) return true;
		if(o instanceof ManifestElement) {
			if(((ManifestElement)o).name.equals(name)) return true;
		}
		return false;
	}

	public void freeData(ObjectContainer container, boolean persistForever) {
		if(data != null) {
			if(persistForever)
				container.activate(data, 1);
			data.free();
			if(persistForever)
				data.removeFrom(container);
			data = null;
		}
		if(persistForever)
			container.delete(this);
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

	public void removeFrom(ObjectContainer container) {
		container.activate(data, 1);
		data.removeFrom(container);
		container.activate(targetURI, 5);
		targetURI.removeFrom(container);
		container.delete(this);
	}
}
