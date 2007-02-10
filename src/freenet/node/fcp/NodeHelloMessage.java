/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.node.Node;
import freenet.node.NodeStarter;
import freenet.node.Version;
import freenet.support.Fields;
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
	public static final String name = "NodeHello";
	String nodeVersion;
	String nodeFCPVersion;
	String nodeNode;
	String nodeCompressionCodecs;
	boolean isTestnet;
	
	private Node node;
	
	public NodeHelloMessage(SimpleFieldSet fs) throws MessageInvalidException {	
		this.nodeNode = fs.get("Node");
		if(nodeNode == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No Node!", null, false);
		else if(!nodeNode.equals("Fred"))
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Not talking to Fred!", null, false);
		
		this.nodeFCPVersion = fs.get("FCPVersion");
		if(nodeFCPVersion == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No FCPVersion!", null, false);
		else if(!nodeFCPVersion.equals("2.0"))
			throw new MessageInvalidException(ProtocolErrorMessage.NOT_SUPPORTED, "FCPVersion is incompatible!", null, false);
		
		this.nodeVersion = fs.get("Version");
		if(nodeVersion == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No Version!", null, false);
		else if(!nodeVersion.startsWith("Fred,0.7,1.0,"))
			throw new MessageInvalidException(ProtocolErrorMessage.NOT_SUPPORTED, "Fred Version is incompatible!", null, false);
		
		this.nodeCompressionCodecs = fs.get("CompressionCodecs");
		if(nodeCompressionCodecs == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No CompressionCodecs!", null, false);
		
		this.isTestnet = Fields.stringToBool(fs.get("Testnet"), false);
	}
	
	public NodeHelloMessage(final Node node) {
		this.node = node;
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
		return sfs;
	}

	public String getName() {
		return NodeHelloMessage.name;
	}

	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "NodeHello goes from server to client not the other way around", null, false);
	}

}
