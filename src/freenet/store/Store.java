/*
 * Dijjer - A Peer to Peer HTTP Cache
 * Copyright (C) 2004,2005 Change.Tv, Inc
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package freenet.store;

import java.io.*;
import java.util.*;

import freenet.keys.Key;

public abstract class Store {

	public static final String VERSION = "$Id: Store.java,v 1.2 2005/06/09 15:58:30 amphibian Exp $";

	private final RandomAccessFile _blockStore;
	private final long _maxBlocks;

	// Starting with least recent - ie. lowest lastAccessTime
	private final LinkedList _accessTimeList = new LinkedList();
	private final HashMap _keyMap = new HashMap();
	private final Vector _recordNumberList = new Vector();

	protected Store(RandomAccessFile blockStoreFile, long maxBlocks) throws Exception {
	    _blockStore = blockStoreFile;
	    _maxBlocks = maxBlocks;
	}
	
	protected Store(File blockStoreFile, long maxBlocks) throws Exception {
		if (!blockStoreFile.exists()) {
			blockStoreFile.createNewFile();
		}
		_blockStore = new RandomAccessFile(blockStoreFile, "rw");
		_maxBlocks = maxBlocks;
	}

	public void shutdown(boolean exit) throws IOException {
		_blockStore.close();
	}
	
	public long getMaxBlocks() {
		return _maxBlocks;
	}

	public float getAgeByKey(Key key) {
		return ((float) _accessTimeList.indexOf(getKeyMap().get(key))) /((float) _accessTimeList.size());
	}
	
	protected RandomAccessFile getBlockStore() {
		return _blockStore;
	}

	protected LinkedList getAccessTimeList() {
		return _accessTimeList;
	}

	protected HashMap getKeyMap() {
		return _keyMap;
	}

	protected Vector getRecordNumberList() {
		return _recordNumberList;
	}

	protected abstract void readStore() throws IOException;

	public synchronized void delete(Key key) throws IOException {
		Block block = (Block) getKeyMap().get(key);
		if (block != null) {
			deleteBlock(block, true);
		}
	}

	protected abstract void deleteBlock(Block block, boolean wipe)  throws IOException;

	protected void updateLastAccess(Block block) {
		_accessTimeList.remove(block);
		ListIterator i = _accessTimeList.listIterator();
		while (true) {
			if (!i.hasNext()) {
				i.add(block);
				break;
			}
			Block nb = (Block) i.next();
			if (nb.getLastAccessTime() > block.getLastAccessTime()) {
				i.previous();
				i.add(block);
				break;
			}
		}
	}

	public synchronized void close() throws IOException {
		getBlockStore().close();
	}
}
