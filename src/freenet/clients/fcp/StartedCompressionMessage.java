/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;
import freenet.support.compress.Compressor.COMPRESSOR_TYPE;

public class StartedCompressionMessage extends FCPMessage {

	final String identifier;
	final boolean global;
	
	final COMPRESSOR_TYPE codec;
	
	public StartedCompressionMessage(String identifier, boolean global, COMPRESSOR_TYPE codec) {
		this.identifier = identifier;
		this.codec = codec;
		this.global = global;
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.putSingle("Identifier", identifier);
		fs.putSingle("Codec", codec.name);
		fs.put("Global", global);
		return fs;
	}

	@Override
	public String getName() {
		return "StartedCompression";
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "StartedCompression goes from server to client not the other way around", identifier, global);
	}

}
