/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import freenet.client.FetchResult;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class DataFoundMessage extends FCPMessage {

	final String identifier;
	final boolean global;
	final String mimeType;
	final long dataLength;
	final long startupTime, completionTime;
	
	public DataFoundMessage(FetchResult fr, String identifier, boolean global, long startupTime, long completionTime) {
		this.identifier = identifier;
		this.global = global;
		this.mimeType = fr.getMimeType();
		this.dataLength = fr.size();
		this.startupTime = startupTime;
		this.completionTime = completionTime;
	}

	public DataFoundMessage(long foundDataLength, String foundDataMimeType, String identifier, boolean global, long startupTime, long completionTime) {
		this.mimeType = foundDataMimeType;
		this.identifier = identifier;
		this.global = global;
		this.dataLength = foundDataLength;
		this.startupTime = startupTime;
		this.completionTime = completionTime;
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.putSingle("Identifier", identifier);
		fs.put("Global", global);
		fs.putSingle("Metadata.ContentType", mimeType);
		fs.put("DataLength", dataLength);
		fs.put("StartupTime", startupTime);
		fs.put("CompletionTime", completionTime);
		return fs;
	}

	@Override
	public String getName() {
		return "DataFound";
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "DataFound goes from server to client not the other way around", identifier, global);
	}

}
