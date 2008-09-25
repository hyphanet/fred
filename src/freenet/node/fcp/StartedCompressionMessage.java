/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import com.db4o.ObjectContainer;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class StartedCompressionMessage extends FCPMessage {

	final String identifier;
	final boolean global;
	
	final int codec;
	
	public StartedCompressionMessage(String identifier, boolean global, int codec) {
		this.identifier = identifier;
		this.codec = codec;
		this.global = global;
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.putSingle("Identifier", identifier);
		fs.put("Codec", codec);
		if(global) fs.putSingle("Global", "true");
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

	public void removeFrom(ObjectContainer container) {
		container.delete(this);
	}

}
