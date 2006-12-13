/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.io.BucketFactory;
import freenet.support.io.BucketTools;


public abstract class DataCarryingMessage extends BaseDataCarryingMessage {

	protected Bucket bucket;
	
	Bucket createBucket(BucketFactory bf, long length, FCPServer server) throws IOException {
		return bf.makeBucket(length);
	}
	
	abstract String getIdentifier();
	abstract boolean isGlobal();

	protected boolean freeOnSent;
	
	void setFreeOnSent() {
		freeOnSent = true;
	}

	public void readFrom(InputStream is, BucketFactory bf, FCPServer server) throws IOException, MessageInvalidException {
		long len = dataLength();
		if(len < 0)
			throw new IllegalArgumentException("Invalid length: "+len);
		if(len == 0) return;
		Bucket tempBucket;
		try {
			tempBucket = createBucket(bf, len, server);
		} catch (IOException e) {
			Logger.error(this, "Bucket error: "+e, e);
			throw new MessageInvalidException(ProtocolErrorMessage.INTERNAL_ERROR, e.toString(), getIdentifier(), isGlobal());
		}
		BucketTools.copyFrom(tempBucket, is, len);
		this.bucket = tempBucket;
	}
	
	protected void writeData(OutputStream os) throws IOException {
		BucketTools.copyTo(bucket, os, dataLength());
		if(freeOnSent) bucket.free();
	}
	
	String getEndString() {
		return "Data";
	}
	
}
