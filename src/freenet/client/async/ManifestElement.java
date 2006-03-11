package freenet.client.async;

import freenet.support.Bucket;

/**
 * Represents an element in a manifest. Fed to SimpleManifestPutter.
 */
public class ManifestElement {

	/** Filename */
	final String name;
	
	/** Data to be inserted */
	final Bucket data;
	
	/** MIME type override. null => use default for filename */
	final String mimeOverride;
	
	public ManifestElement(String name, Bucket data, String mimeOverride) {
		this.name = name;
		this.data = data;
		this.mimeOverride = mimeOverride;
	}
	
	public ManifestElement(ManifestElement me, String fullName) {
		this.name = fullName;
		this.data = me.data;
		this.mimeOverride = me.mimeOverride;
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
}
