package freenet.node.fcp;

import freenet.client.FetchResult;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class DataFoundMessage extends FCPMessage {

	final String identifier;
	final String mimeType;
	final long dataLength;
	
	public DataFoundMessage(FetchResult fr, String identifier) {
		this.identifier = identifier;
		this.mimeType = fr.getMimeType();
		this.dataLength = fr.size();
	}

	public DataFoundMessage(long foundDataLength, String foundDataMimeType, String identifier2) {
		this.mimeType = foundDataMimeType;
		this.identifier = identifier2;
		this.dataLength = foundDataLength;
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(false);
		fs.put("Identifier", identifier);
		fs.put("Metadata.ContentType", mimeType);
		fs.put("DataLength", Long.toString(dataLength));
		return fs;
	}

	public String getName() {
		return "DataFound";
	}

	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "DataFound goes from server to client not the other way around", identifier);
	}

}
