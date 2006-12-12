/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.client.FetchResult;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class DataFoundMessage extends FCPMessage {

	final String identifier;
	final boolean global;
	final String mimeType;
	final long dataLength;
	
	public DataFoundMessage(FetchResult fr, String identifier, boolean global) {
		this.identifier = identifier;
		this.global = global;
		this.mimeType = fr.getMimeType();
		this.dataLength = fr.size();
	}

	public DataFoundMessage(long foundDataLength, String foundDataMimeType, String identifier, boolean global) {
		this.mimeType = foundDataMimeType;
		this.identifier = identifier;
		this.global = global;
		this.dataLength = foundDataLength;
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet();
		fs.put("Identifier", identifier);
		if(global) fs.put("Global", "true");
		fs.put("Metadata.ContentType", mimeType);
		fs.put("DataLength", Long.toString(dataLength));
		return fs;
	}

	public String getName() {
		return "DataFound";
	}

	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "DataFound goes from server to client not the other way around", identifier, global);
	}

}
