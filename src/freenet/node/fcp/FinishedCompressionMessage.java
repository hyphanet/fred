/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.client.events.FinishedCompressionEvent;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class FinishedCompressionMessage extends FCPMessage {

	final String identifier;
	final boolean global;
	final int codec;
	final long origSize;
	final long compressedSize;
	
	public FinishedCompressionMessage(String identifier, boolean global, FinishedCompressionEvent event) {
		this.identifier = identifier;
		this.codec = event.codec;
		this.compressedSize = event.compressedSize;
		this.origSize = event.originalSize;
		this.global = global;
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet();
		fs.put("Identifier", identifier);
		fs.put("Codec", Integer.toString(codec));
		fs.put("OriginalSize", Long.toString(origSize));
		fs.put("CompressedSize", Long.toString(compressedSize));
		if(global) fs.put("Global", "true");
		return fs;
	}

	public String getName() {
		return "FinishedCompression";
	}

	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "FinishedCompression goes from server to client not the other way around", identifier, global);
	}

}
