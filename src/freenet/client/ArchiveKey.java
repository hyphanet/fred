/*
  ArchiveKey.java / Freenet
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

import freenet.keys.FreenetURI;

public class ArchiveKey {

	final FreenetURI key;
	final String filename;
	
	public ArchiveKey(FreenetURI key2, String filename2) {
		key = key2;
		filename = filename2;
	}

	public boolean equals(Object o) {
		if((o == null) || !(o instanceof ArchiveKey)) return false;
		if(this == o) return true;
		
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