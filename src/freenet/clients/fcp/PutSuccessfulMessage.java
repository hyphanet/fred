/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class PutSuccessfulMessage extends FCPMessage {

	public final String identifier;
	public final boolean global;
	public final FreenetURI uri;
	public final long startupTime, completionTime;
	
	public PutSuccessfulMessage(String identifier, boolean global, FreenetURI uri, long startupTime, long completionTime) {
		this.identifier = identifier;
		this.global = global;
		this.uri = uri;
		this.startupTime = startupTime;
		this.completionTime = completionTime;
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.putSingle("Identifier", identifier);
		fs.put("Global", global);
		// This is useful for simple clients.
		if(uri != null)
			fs.putSingle("URI", uri.toString(false, false));
		fs.put("StartupTime", startupTime);
		fs.put("CompletionTime", completionTime);
		return fs;
	}

	@Override
	public String getName() {
		return "PutSuccessful";
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "InsertSuccessful goes from server to client not the other way around", identifier, global);
	}

}
