/**
 * 
 */
package freenet.client;

import java.io.File;

import freenet.support.PaddedEncryptedBucket;
import freenet.support.io.FileBucket;

class TempStoreElement {
	TempStoreElement(File myFile, FileBucket fb, PaddedEncryptedBucket encryptedBucket) {
		this.myFilename = myFile;
		this.underBucket = fb;
		this.bucket = encryptedBucket;
	}
	
	File myFilename;
	PaddedEncryptedBucket bucket;
	FileBucket underBucket;
	
	public void finalize() {
		underBucket.finalize();
	}
}