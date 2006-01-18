package freenet.node.fcp;

import freenet.node.Node;
import freenet.node.Version;
import freenet.support.SimpleFieldSet;

/**
 * NodeHello
 *
 * NodeHello
 * FCPVersion=<protocol version>
 * Node=Fred
 * Version=0.7.0,401
 * EndMessage
 */
public class NodeHelloMessage extends FCPMessage {

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet sfs = new SimpleFieldSet();
		// FIXME
		sfs.put("FCPVersion", "0.7.0");
		sfs.put("Node", "Fred");
		sfs.put("Version", Version.getVersionString());
		return sfs;
	}

	public String getName() {
		return "NodeHello";
	}

	public void run(FCPConnectionHandler handler, Node node) {
		throw new UnsupportedOperationException();
		// Client should not be sending this!
	}

}
