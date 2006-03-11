package freenet.client.async;

import freenet.support.Bucket;

/**
 * Represents an element in a manifest. Fed to SimpleManifestPutter.
 */
public class ManifestElement {

	/** Filename */
	final String name;
	
	/** Data to be inserted. Can be null, if the insert has completed. */
	final Bucket data;
	
	/** MIME type override. null => use default for filename */
	final String mimeOverride;
	
	/** Original size of the bucket. Can be set explicitly even if data == null. */
	final long dataSize;
	
	public ManifestElement(String name, Bucket data, String mimeOverride, long size) {
		this.name = name;
		this.data = data;
		this.mimeOverride = mimeOverride;
		this.dataSize = size;
	}
	
	public ManifestElement(ManifestElement me, String fullName) {
		this.name = fullName;
		this.data = me.data;
		this.mimeOverride = me.mimeOverride;
		this.dataSize = me.dataSize;
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
}
