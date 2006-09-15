/*
  Block.java / Freenet, Dijjer - A Peer to Peer HTTP Cache
  Copyright (C) 2005-2006 The Free Network project
  Copyright (C) 2004,2005 Change.Tv, Inc
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

package freenet.store;

import freenet.keys.Key;

public class Block {

	public static final String VERSION = "$Id: Block.java,v 1.1 2005/02/10 00:12:06 amphibian Exp $";

	private int _recordNumber;
	private long _lastAccessTime;
	private Key _key;

	public Block(int recordNum, Key key, long accessTime) {
		_recordNumber = recordNum;
		_key = key;
		_lastAccessTime = accessTime;
	}

	public int getRecordNumber() {
		return _recordNumber;
	}

	public void setRecordNumber(int newRecNo) {
		_recordNumber = newRecNo;
	}

	public Key getKey() {
		return _key;
	}

	public long lastAccessTime() {
		return _lastAccessTime;
	}

	public String toString() {
		return "key: " + _key + " lastAccess: " + _lastAccessTime + " rec: " + _recordNumber;
	}

	public long getLastAccessTime() {
		return _lastAccessTime;
	}

	public void setLastAccessTime(long accessTime) {
		_lastAccessTime = accessTime;
	}
	
	public void setKey(Key key) {
		_key = key;
	}

}
