package freenet.client;

import freenet.keys.FreenetURI;

public class ArchiveKey {

	final FreenetURI key;
	final String filename;
	
	public ArchiveKey(FreenetURI key2, String filename2) {
		key = key2;
		filename = filename2;
	}

	public boolean equals(Object o) {
		if(this == o) return true;
		if(!(o instanceof ArchiveKey)) return false;
		ArchiveKey cmp = ((ArchiveKey)o);
		return (cmp.key.equals(key) && cmp.filename.equals(filename));
	}
	
	public int hashCode() {
		return key.hashCode() ^ filename.hashCode();
	}
	
	public String toString() {
		return key+":"+filename;
	}
}