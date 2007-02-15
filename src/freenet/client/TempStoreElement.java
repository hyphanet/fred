/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import java.io.File;

import freenet.support.io.FileBucket;
import freenet.support.io.PaddedEphemerallyEncryptedBucket;

class TempStoreElement {
	TempStoreElement(File myFile, FileBucket fb, PaddedEphemerallyEncryptedBucket encryptedBucket) {
		this.myFilename = myFile;
		this.underBucket = fb;
		this.bucket = encryptedBucket;
	}
	
	File myFilename;
	PaddedEphemerallyEncryptedBucket bucket;
	FileBucket underBucket;
	
	public void close() {
		underBucket.free();
	}
}