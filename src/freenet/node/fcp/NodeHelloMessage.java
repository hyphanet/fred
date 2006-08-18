package freenet.node.fcp;

import freenet.node.Node;
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
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No Node!", null);
		else if(!nodeNode.equals("Fred"))
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Not talking to Fred!", null);
		
		this.nodeFCPVersion = fs.get("FCPVersion");
		if(nodeFCPVersion == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No FCPVersion!", null);
		else if(!nodeFCPVersion.equals("2.0"))
			throw new MessageInvalidException(ProtocolErrorMessage.NOT_SUPPORTED, "FCPVersion is incompatible!", null);
		
		this.nodeVersion = fs.get("Version");
		if(nodeVersion == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No Version!", null);
		else if(!nodeVersion.startsWith("Fred,0.7,1.0,"))
			throw new MessageInvalidException(ProtocolErrorMessage.NOT_SUPPORTED, "Fred Version is incompatible!", null);
		
		this.nodeCompressionCodecs = fs.get("CompressionCodecs");
		if(nodeCompressionCodecs == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No CompressionCodecs!", null);	
		
		this.isTestnet = Fields.stringToBool(fs.get("Testnet"), false);
	}
	
	public NodeHelloMessage(final Node node) {
		this.node = node;
	}
	
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet sfs = new SimpleFieldSet();
		// FIXME
		sfs.put("FCPVersion", "2.0");
		sfs.put("Node", "Fred");
		sfs.put("Version", Version.getVersionString());
		sfs.put("Testnet", Boolean.toString(node == null ? false : node.isTestnetEnabled()));
		sfs.put("CompressionCodecs", Integer.toString(Compressor.countCompressAlgorithms()));
		return sfs;
	}

	public String getName() {
		return NodeHelloMessage.name;
	}

	public void run(FCPConnectionHandler handler, Node node) {
		throw new UnsupportedOperationException();
		// Client should not be sending this!
	}

}
