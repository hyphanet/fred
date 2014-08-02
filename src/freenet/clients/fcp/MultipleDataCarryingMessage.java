package freenet.clients.fcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.io.BucketTools;

public abstract class MultipleDataCarryingMessage extends BaseDataCarryingMessage {

	//The iteration order matters, hence a LinkedHashMap
	protected Map<String, Bucket> buckets = new LinkedHashMap<String, Bucket>();
	
	protected boolean freeOnSent;
	
	void setFreeOnSent() {
		freeOnSent = true;
	}

	//We can't read an arbitrary multiple data carrying message from an InputStream
	//This class is only used to send such messages to the client
	@Override
	public void readFrom(InputStream is, BucketFactory bf, FCPServer server) throws IOException, MessageInvalidException {
		throw new UnsupportedOperationException();
	}
	
	@Override
	protected void writeData(OutputStream os) throws IOException {
			for(Map.Entry<String, Bucket> entry : buckets.entrySet()) {
				Bucket bucket = entry.getValue();
				BucketTools.copyTo(bucket, os, bucket.size());
				if(freeOnSent) bucket.free(); // Always transient so no removeFrom() needed.
			}
	}
	
	@Override
	public SimpleFieldSet getFieldSet() {
		int dataLength = 0;
		SimpleFieldSet fs = new SimpleFieldSet(true);
		for(Map.Entry<String, Bucket> entry : buckets.entrySet()) {
			String field = entry.getKey();
			Bucket bucket = entry.getValue();
			fs.put(field + "Length", bucket.size());
			dataLength += bucket.size();
		}
		fs.put("DataLength", dataLength);
		return fs;
	}
	
	@Override
	public long dataLength() {
		int dataLength = 0;
		for(Bucket bucket : buckets.values())
			dataLength += bucket.size();
		return dataLength;
	}
	
	@Override
	String getEndString() {
		return "Data";
	}
}