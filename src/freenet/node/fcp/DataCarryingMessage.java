package freenet.node.fcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import freenet.support.Bucket;
import freenet.support.BucketFactory;
import freenet.support.BucketTools;
import freenet.support.Logger;


public abstract class DataCarryingMessage extends BaseDataCarryingMessage {

	protected Bucket bucket;
	
	Bucket createBucket(BucketFactory bf, long length, FCPServer server) throws IOException {
		return bf.makeBucket(length);
	}
	
	abstract String getIdentifier();

	protected boolean freeOnSent;
	
	void setFreeOnSent() {
		freeOnSent = true;
	}

	public void readFrom(InputStream is, BucketFactory bf, FCPServer server) throws IOException, MessageInvalidException {
		long len = dataLength();
		if(len < 0)
			throw new IllegalArgumentException("Invalid length: "+len);
		if(len == 0) return;
		Bucket bucket;
		try {
			bucket = createBucket(bf, len, server);
		} catch (IOException e) {
			Logger.error(this, "Bucket error: "+e, e);
			throw new MessageInvalidException(ProtocolErrorMessage.INTERNAL_ERROR, e.toString(), getIdentifier());
		}
		BucketTools.copyFrom(bucket, is, len);
		this.bucket = bucket;
	}
	
	protected void writeData(OutputStream os) throws IOException {
		BucketTools.copyTo(bucket, os, dataLength());
		if(freeOnSent) bucket.free();
	}
	
	String getEndString() {
		return "Data";
	}
	
}
