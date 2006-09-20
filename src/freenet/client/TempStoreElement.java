/**
 * 
 */
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
	
	public void finalize() {
		underBucket.finalize();
	}
}