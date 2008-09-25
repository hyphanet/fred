/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import freenet.keys.FreenetURI;

public class ArchiveKey {

	final FreenetURI key;
	final String filename;
	
	public ArchiveKey(FreenetURI key2, String filename2) {
		key = key2;
		filename = filename2;
	}

	@Override
	public boolean equals(Object o) {
		if((o == null) || !(o instanceof ArchiveKey)) return false;
		if(this == o) return true;
		
		ArchiveKey cmp = ((ArchiveKey)o);
		return (cmp.key.equals(key) && cmp.filename.equals(filename));
	}
	
	@Override
	public int hashCode() {
		return key.hashCode() ^ filename.hashCode();
	}
	
	@Override
	public String toString() {
		return key+":"+filename;
	}
}