/*
  RealArchiveStoreItem.java / Freenet
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

import java.io.File;

import freenet.keys.FreenetURI;
import freenet.support.io.Bucket;
import freenet.support.io.FileBucket;
import freenet.support.io.FileUtil;
import freenet.support.io.PaddedEphemerallyEncryptedBucket;

class RealArchiveStoreItem extends ArchiveStoreItem {

	private final ArchiveManager manager;
	boolean finalized;
	File myFilename;
	PaddedEphemerallyEncryptedBucket bucket;
	FileBucket underBucket;
	
	/**
	 * Create an ArchiveStoreElement from a TempStoreElement.
	 * @param key2 The key of the archive the file came from.
	 * @param realName The name of the file in that archive.
	 * @param temp The TempStoreElement currently storing the data.
	 * @param manager The parent ArchiveManager within which this item is stored.
	 */
	RealArchiveStoreItem(ArchiveManager manager, ArchiveStoreContext ctx, FreenetURI key2, String realName, TempStoreElement temp) {
		super(new ArchiveKey(key2, realName), ctx);
		this.manager = manager;
		this.finalized = false;
		this.bucket = temp.bucket;
		this.underBucket = temp.underBucket;
		underBucket.dontDeleteOnFinalize();
		underBucket.setReadOnly();
		this.myFilename = underBucket.getFile();
		this.manager.cachedData += spaceUsed();
	}

	/**
	 * Return the data, as a Bucket, in plaintext.
	 */
	Bucket dataAsBucket() {
		return bucket;
	}

	/**
	 * Return the length of the data.
	 */
	long dataSize() {
		return bucket.size();
	}

	/**
	 * Return the estimated space used by the data.
	 */
	synchronized long spaceUsed() {
		return FileUtil.estimateUsage(myFilename, underBucket.size());
	}
	
	public synchronized void finalize() {
		super.finalize();
		if(finalized) return;
		long sz = spaceUsed();
		underBucket.finalize();
		finalized = true;
		this.manager.cachedData -= sz;
	}

	Bucket getDataOrThrow() throws ArchiveFailureException {
		return dataAsBucket();
	}
}