package freenet.node.fcp;

import freenet.node.Node;
import freenet.node.Version;
import freenet.support.SimpleFieldSet;
import freenet.support.compress.Compressor;

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

	private final Node node;
	
	public NodeHelloMessage(Node node) {
		this.node = node;
	}
	
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet sfs = new SimpleFieldSet();
		// FIXME
		sfs.put("FCPVersion", "2.0");
		sfs.put("Node", "Fred");
		sfs.put("Version", Version.getVersionString());
		sfs.put("Testnet", Boolean.toString(node.isTestnetEnabled()));
		sfs.put("CompressionCodecs", Integer.toString(Compressor.countCompressAlgorithms()));
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
