/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 * All the data, all in one big chunk. Obviously we must already have
 * all the data to send it. We do not want to have to block on a request,
 * especially as there may be errors.
 */
public class AllDataMessage extends DataCarryingMessage {

	final long dataLength;
	final boolean global;
	final String identifier;
	
	public AllDataMessage(Bucket bucket, String identifier, boolean global) {
		this.bucket = bucket;
		this.dataLength = bucket.size();
		this.identifier = identifier;
		this.global = global;
	}

	long dataLength() {
		return dataLength;
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.putSingle("DataLength", Long.toString(dataLength));
		fs.putSingle("Identifier", identifier);
		if(global) fs.putSingle("Global", "true");
		return fs;
	}

	public String getName() {
		return "AllData";
	}

	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "AllData goes from server to client not the other way around", identifier, global);
	}

	String getIdentifier() {
		return identifier;
	}

	boolean isGlobal() {
		return global;
	}

}
