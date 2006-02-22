package freenet.node.fcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import freenet.support.Bucket;
import freenet.support.BucketFactory;
import freenet.support.BucketTools;


public abstract class DataCarryingMessage extends FCPMessage {

	protected Bucket bucket;
	
	abstract long dataLength();
	
	protected boolean freeOnSent;
	
	void setFreeOnSent() {
		freeOnSent = true;
	}

	public void readFrom(InputStream is, BucketFactory bf) throws IOException {
		long len = dataLength();
		if(len < 0)
			throw new IllegalArgumentException("Invalid length: "+len);
		if(len == 0) return;
		Bucket bucket = bf.makeBucket(len);
		BucketTools.copyFrom(bucket, is, len);
		this.bucket = bucket;
	}
	
	public void send(OutputStream os) throws IOException {
		super.send(os);
		BucketTools.copyTo(bucket, os, dataLength());
		if(freeOnSent) bucket.free();
	}

	String getEndString() {
		return "Data";
	}
	
}
