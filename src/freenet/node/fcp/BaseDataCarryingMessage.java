package freenet.node.fcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import freenet.support.api.BucketFactory;

public abstract class BaseDataCarryingMessage extends FCPMessage {

	abstract long dataLength();
	
	public abstract void readFrom(InputStream is, BucketFactory bf, FCPServer server) throws IOException, MessageInvalidException;
	
	public void send(OutputStream os) throws IOException {
		super.send(os);
		writeData(os);
	}

	protected abstract void writeData(OutputStream os) throws IOException;

}
