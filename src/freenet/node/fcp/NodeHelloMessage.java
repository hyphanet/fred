/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.node.Node;
import freenet.node.NodeStarter;
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
	public static final String NAME = "NodeHello";
	String nodeVersion;
	String nodeFCPVersion;
	String nodeNode;
	String nodeCompressionCodecs;
	boolean isTestnet;
	
	private final Node node;
	private final String id;
		
	public NodeHelloMessage(final Node node, String id) {
		this.node = node;
		this.id = id;
	}
	
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet sfs = new SimpleFieldSet(true);
		// FIXME
		sfs.putSingle("FCPVersion", "2.0");
		sfs.putSingle("Node", "Fred");
		sfs.putSingle("Version", Version.getVersionString());
		sfs.put("Build", Version.buildNumber());
		sfs.putSingle("Revision", Version.cvsRevision);
		sfs.put("ExtBuild", NodeStarter.extBuildNumber);
		sfs.putSingle("ExtRevision", NodeStarter.extRevisionNumber);
		sfs.putSingle("Testnet", Boolean.toString(node == null ? false : node.isTestnetEnabled()));
		sfs.putSingle("CompressionCodecs", Integer.toString(Compressor.countCompressAlgorithms()));
		sfs.putSingle("ConnectionIdentifier", id);
		return sfs;
	}

	public String getName() {
		return NodeHelloMessage.NAME;
	}

	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "NodeHello goes from server to client not the other way around", null, false);
	}

}
