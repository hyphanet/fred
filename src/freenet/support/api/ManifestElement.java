/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.api;

import java.io.Serializable;

import freenet.client.async.ClientContext;
import freenet.client.DefaultMIMETypes;
import freenet.keys.FreenetURI;
import freenet.support.io.ResumeFailedException;

/**
 * Represents an element in a manifest. Fed to *ManifestPutter. An element can be a file or a 
 * redirect.
 */
public class ManifestElement implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Filename */
	final String name;
	
	/** Full name in the container it is inserted as part of. */
	public final String fullName;
	
	/** Data to be inserted. Can be null, if the insert has completed. */
	final RandomAccessBucket data;
	
	/** MIME type override. null => use default for filename */
	public final String mimeOverride;
	
	/** Original size of the bucket. Can be set explicitly even if data == null. */
	final long dataSize;
	
	/** Redirect target */
	public final FreenetURI targetURI;
	
	/** Construct a ManifestElement for a file. */
	public ManifestElement(String name2, String fullName2, RandomAccessBucket data2, String mimeOverride2, long size) {
		this.name = name2;
		this.fullName = fullName2;
		this.data = data2;
		assert(data != null);
		this.mimeOverride = mimeOverride2;
		this.dataSize = size;
		this.targetURI = null;
	}
	
	public ManifestElement(String name2, RandomAccessBucket data2, String mimeOverride2, long size2) {
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
	
    public ManifestElement(String name2, String fullName2, String mimeOverride2,
            FreenetURI targetURI2) {
        this.name = name2;
        this.fullName = fullName2;
        this.mimeOverride = mimeOverride2;
        this.targetURI = targetURI2;
        this.data = null;
        this.dataSize = -1;
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

	public void freeData() {
		if(data != null) {
			data.free();
		}
	}

	public String getName() {
		return name;
	}

	public String getMimeTypeOverride() {
		return mimeOverride;
	}
	/**
	 * A MIME type to feed into ClientMetadata.
	 */
	public String getMimeType() {
		String mimeType = mimeOverride;
		if((mimeOverride == null) && (name != null))
			mimeType = DefaultMIMETypes.guessMIMEType(name, true);
		return mimeType;
	}

	public RandomAccessBucket getData() {
		return data;
	}
	
	public long getSize() {
		return dataSize;
	}

	public FreenetURI getTargetURI() {
		return targetURI;
	}

    public void onResume(ClientContext context) throws ResumeFailedException {
        if(data != null) data.onResume(context);
    }

}
