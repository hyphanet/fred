/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.keys.FreenetURI;
import freenet.support.api.Bucket;

/**
 * Represents an element in a manifest. Fed to SimpleManifestPutter. An element can be a file or a 
 * redirect.
 */
public class ManifestElement {

	/** Filename */
	final String name;
	
	/** Full name in the container it is inserted as part of. */
	final String fullName;
	
	/** Data to be inserted. Can be null, if the insert has completed. */
	Bucket data;
	
	/** MIME type override. null => use default for filename */
	final String mimeOverride;
	
	/** Original size of the bucket. Can be set explicitly even if data == null. */
	final long dataSize;
	
	/** Redirect target */
	final FreenetURI targetURI;
	
	/** Construct a ManifestElement for a file. */
	public ManifestElement(String name2, String fullName2, Bucket data2, String mimeOverride2, long size) {
		this.name = name2;
		this.fullName = fullName2;
		this.data = data2;
		assert(data != null);
		this.mimeOverride = mimeOverride2;
		this.dataSize = size;
		this.targetURI = null;
	}
	
	public ManifestElement(String name2, Bucket data2, String mimeOverride2, long size2) {
		this.name = name2;
		this.fullName = name2;
		this.data = data2;
		this.mimeOverride = mimeOverride2;
		this.dataSize = size2;
		this.targetURI = null;
	}
	
	/** Copy and change name */
	public ManifestElement(ManifestElement me, String newName) {
		this.name = newName;
		this.fullName = me.fullName;
		this.data = me.data;
		this.mimeOverride = me.mimeOverride;
		this.dataSize = me.dataSize;
		this.targetURI = me.targetURI;
	}
	
	/** Copy and change full name */
	public ManifestElement(ManifestElement me, String newName, String newFullName) {
		this.name = newName;
		this.fullName = newFullName;
		assert(fullName != null);
		this.data = me.data;
		this.mimeOverride = me.mimeOverride;
		this.dataSize = me.dataSize;
		this.targetURI = me.targetURI;
	}

	/** Construct a ManifestElement for a redirect */
	public ManifestElement(String name2, FreenetURI targetURI2, String mimeOverride2) {
		this.name = name2;
		this.fullName = name2;
		this.data = null;
		this.mimeOverride = mimeOverride2;
		this.dataSize = -1;
		this.targetURI = targetURI2;
		assert(targetURI != null);
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
