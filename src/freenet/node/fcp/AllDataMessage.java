package freenet.node.fcp;

import freenet.node.Node;
import freenet.support.Bucket;
import freenet.support.SimpleFieldSet;

/**
 * All the data, all in one big chunk. Obviously we must already have
 * all the data to send it. We do not want to have to block on a request,
 * especially as there may be errors.
 */
public class AllDataMessage extends DataCarryingMessage {

	final long dataLength;
	final String identifier;
	
	public AllDataMessage(FCPConnectionHandler handler, Bucket bucket, String identifier) {
		this.bucket = bucket;
		this.dataLength = bucket.size();
		this.identifier = identifier;
	}

	long dataLength() {
		return dataLength;
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(false);
		fs.put("DataLength", Long.toString(dataLength));
		fs.put("Identifier", identifier);
		return fs;
	}

	public String getName() {
		return "AllData";
	}

	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "AllData goes from server to client not the other way around", identifier);
	}

}
