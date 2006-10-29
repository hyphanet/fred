/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.node.Node;
import freenet.node.PeerNode;
import freenet.support.SimpleFieldSet;

public class ConfigData extends FCPMessage {
	static final String name = "ConfigData";
	
	final Node node;
	final boolean withDefaults;
	
	public ConfigData(Node node, boolean withDefaults) {
		this.node = node;
		this.withDefaults = withDefaults;
	}
	
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet();
		if(withDefaults) {
			fs = node.config.exportFieldSet(true);
		} else {
			fs = node.config.exportFieldSet(false);
		}
		return fs;
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "ConfigData goes from server to client not the other way around", null);
	}

}
